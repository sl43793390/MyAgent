package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Built-in tool for reading the content of a text file,
 * with optional line range (offset/limit) support.
 */
public class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private static final int MAX_CHARS = 20000;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "The file path to read"
                ),
                "offset", Map.of(
                        "type", "integer",
                        "description", "1-based line number to start reading from (optional, default 1)"
                ),
                "limit", Map.of(
                        "type", "integer",
                        "description", "Maximum number of lines to read (optional, default all lines)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"path"}
        );

        return new ToolDefinition(
                "file_read",
                "Read the content of a text file. Each line is prefixed with its 1-based line number. "
                        + "Supports reading a specific range of lines via offset and limit.",
                parameters
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = getStringArg(arguments, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' parameter is required";
        }

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return "Error: file does not exist: " + pathStr;
        }
        if (Files.isDirectory(path)) {
            return "Error: path is a directory, not a file: " + pathStr;
        }

        try {
            int offset = parseIntArg(arguments.get("offset"), 1);
            int limit = parseIntArg(arguments.get("limit"), Integer.MAX_VALUE);

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int totalLines = lines.size();

            int start = Math.max(1, offset) - 1;
            if (start >= totalLines) {
                return "Error: offset " + offset + " is beyond the end of file (total lines: " + totalLines + ")";
            }
            int end = Math.min(totalLines, start + Math.max(1, limit));

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append("\t").append(lines.get(i)).append("\n");
            }

            String result = sb.toString();
            if (result.length() > MAX_CHARS) {
                result = result.substring(0, MAX_CHARS) + "\n... [content truncated]";
            }

            log.debug("Read file {} ({} of {} lines)", pathStr, end - start, totalLines);
            return result;
        } catch (IOException e) {
            log.error("Failed to read file '{}': {}", pathStr, e.getMessage());
            return "Error reading file: " + e.getMessage();
        }
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
