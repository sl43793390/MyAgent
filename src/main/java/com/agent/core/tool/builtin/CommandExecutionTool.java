package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Built-in tool for executing shell/system commands and capturing their output.
 * Uses cmd.exe on Windows and /bin/sh on Unix-like systems.
 */
public class CommandExecutionTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CommandExecutionTool.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 60;
    private static final int MAX_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 10000;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "command", Map.of(
                        "type", "string",
                        "description", "The shell command to execute, e.g. 'ls -la' or 'dir'"
                ),
                "working_dir", Map.of(
                        "type", "string",
                        "description", "Optional working directory for the command (default: current directory)"
                ),
                "timeout_seconds", Map.of(
                        "type", "integer",
                        "description", "Maximum execution time in seconds, up to 300 (optional, default 60)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"command"}
        );

        return new ToolDefinition(
                "command_execute",
                "Execute a shell command and return its exit code, stdout and stderr. "
                        + "Uses cmd.exe on Windows and /bin/sh on Unix-like systems.",
                parameters
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String command = getStringArg(arguments, "command");
        if (command == null || command.isBlank()) {
            return "Error: 'command' parameter is required";
        }

        String workingDirStr = getStringArg(arguments, "working_dir");
        int timeoutSeconds = parseIntArg(arguments.get("timeout_seconds"), DEFAULT_TIMEOUT_SECONDS);
        timeoutSeconds = Math.min(Math.max(1, timeoutSeconds), MAX_TIMEOUT_SECONDS);

        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> commandParts = isWindows
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);

        ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
        if (workingDirStr != null && !workingDirStr.isBlank()) {
            Path workingDir = Path.of(workingDirStr);
            if (!Files.isDirectory(workingDir)) {
                return "Error: working directory does not exist: " + workingDirStr;
            }
            processBuilder.directory(workingDir.toFile());
        }

        Process process;
        try {
            process = processBuilder.start();
        } catch (IOException e) {
            log.error("Failed to start command '{}': {}", command, e.getMessage());
            return "Error starting command: " + e.getMessage();
        }

        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outThread = new Thread(() -> readStream(process.getInputStream(), stdout));
        Thread errThread = new Thread(() -> readStream(process.getErrorStream(), stderr));
        outThread.start();
        errThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return "Error: command execution interrupted";
        }

        if (!finished) {
            process.destroyForcibly();
            joinQuietly(outThread);
            joinQuietly(errThread);
            log.warn("Command '{}' timed out after {} seconds", command, timeoutSeconds);
            return "Error: command timed out after " + timeoutSeconds + " seconds\n"
                    + "--- partial stdout ---\n" + truncate(stdout.toString());
        }

        joinQuietly(outThread);
        joinQuietly(errThread);

        int exitCode = process.exitValue();
        StringBuilder result = new StringBuilder();
        result.append("Exit code: ").append(exitCode).append("\n");
        if (stdout.length() > 0) {
            result.append("\n--- stdout ---\n").append(truncate(stdout.toString()));
        }
        if (stderr.length() > 0) {
            result.append("\n--- stderr ---\n").append(truncate(stderr.toString()));
        }

        log.debug("Command '{}' finished with exit code {}", command, exitCode);
        return result.toString();
    }

    private void readStream(InputStream inputStream, StringBuilder target) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(inputStream, Charset.defaultCharset()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                target.append(line).append("\n");
            }
        } catch (IOException e) {
            // Stream closed when process is destroyed; ignore
        }
    }

    private void joinQuietly(Thread thread) {
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String truncate(String output) {
        if (output.length() > MAX_OUTPUT_CHARS) {
            return output.substring(0, MAX_OUTPUT_CHARS) + "\n... [output truncated]";
        }
        return output;
    }

    private String getStringArg(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value != null ? value.toString() : null;
    }

    private int parseIntArg(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
