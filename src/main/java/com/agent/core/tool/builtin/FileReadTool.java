package com.agent.core.tool.builtin;

import com.agent.core.security.PathSandbox;
import com.agent.core.tool.RiskLevel;
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 读取文本文件，可选只读取其中指定的行范围。
 *
 * <p>路径在访问文件系统前会先经过 {@link PathSandbox} 解析：如果没有这一步，模型只需开口索要，
 * 或被它本应总结的文件里嵌入的指令引导，就能读到 {@code ~/.ssh/id_rsa} 或 {@code /etc/passwd}。
 */
public class FileReadTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileReadTool.class);

    private static final int MAX_CHARS = 20_000;
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final PathSandbox sandbox;

    /** 在进程当前工作目录范围内读取文件。 */
    public FileReadTool() {
        this(PathSandbox.currentDirectory());
    }

    /**
     * @param sandbox 本工具允许读取的目录边界
     */
    public FileReadTool(PathSandbox sandbox) {
        this.sandbox = sandbox != null ? sandbox : PathSandbox.currentDirectory();
    }

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
    public ToolResult execute(Map<String, Object> arguments) {
        Path path;
        try {
            path = Args.path(sandbox, arguments, "path");
        } catch (SecurityException e) {
            log.warn("Rejected file_read: {}", e.getMessage());
            return ToolResult.failure(e.getMessage());
        }

        if (!Files.exists(path)) {
            return ToolResult.failure("Error: file does not exist: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("Error: path is a directory, not a file: " + path);
        }

        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                return ToolResult.failure("Error: file is too large to read ("
                        + size + " bytes, limit is " + MAX_FILE_BYTES + "). "
                        + "Use 'offset' and 'limit' on a smaller file, or read it in ranges.");
            }

            int offset = Args.integer(arguments, "offset", 1);
            int limit = Args.integer(arguments, "limit", Integer.MAX_VALUE);

            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int totalLines = lines.size();

            int start = Math.max(1, offset) - 1;
            if (start >= totalLines) {
                return ToolResult.failure("Error: offset " + offset
                        + " is beyond the end of file (total lines: " + totalLines + ")");
            }
            int end = Math.min(totalLines, start + Math.max(1, limit));

            StringBuilder sb = new StringBuilder();
            for (int i = start; i < end; i++) {
                sb.append(i + 1).append('\t').append(lines.get(i)).append('\n');
            }

            log.debug("Read {} of {} line(s) from {}", end - start, totalLines, path);
            return ToolResult.success(Args.truncate(sb.toString(), MAX_CHARS));

        } catch (IOException e) {
            log.error("Failed to read '{}': {}", path, e.getMessage());
            return ToolResult.failure("Error reading file: " + e.getMessage());
        }
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SAFE;
    }

    /**
     * 本工具被限制在其中运行的沙箱。
     */
    public PathSandbox sandbox() {
        return sandbox;
    }
}
