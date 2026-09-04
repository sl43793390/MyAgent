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
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 将文本写入文件，并自动创建缺失的父目录。
 *
 * <p>这是文件类工具中最危险的一个：它能把「读错文件」升级为「植入后门」。这里有两道防护至关重要，
 * 而之前两者都缺失：
 * <ul>
 *   <li><b>{@link PathSandbox}</b> — 目标路径必须位于允许的根目录之内。此前该工具会为<i>任意</i>路径
 *       自动创建父目录，因此 {@code ~/.ssh/authorized_keys} 曾是一个完全合法的写入目标。</li>
 *   <li><b>大小上限</b> — 写入内容来自模型，因此无限制的写入就等于无限制的磁盘填充。</li>
 * </ul>
 */
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

    /** 防止模型（或提示词注入）在一次调用中把磁盘写满。 */
    private static final int MAX_CONTENT_CHARS = 1_000_000;

    private final PathSandbox sandbox;

    /** 在进程当前工作目录范围内写入文件。 */
    public FileWriteTool() {
        this(PathSandbox.currentDirectory());
    }

    /**
     * @param sandbox 本工具允许写入的目录边界
     */
    public FileWriteTool(PathSandbox sandbox) {
        this.sandbox = sandbox != null ? sandbox : PathSandbox.currentDirectory();
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "The file path to write to"
                ),
                "content", Map.of(
                        "type", "string",
                        "description", "The content to write to the file"
                ),
                "append", Map.of(
                        "type", "boolean",
                        "description", "If true, append to the file instead of overwriting (optional, default false)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"path", "content"}
        );

        return new ToolDefinition(
                "file_write",
                "Write content to a file. Overwrites the file by default; "
                        + "set 'append' to true to append to the end of the file. "
                        + "Missing parent directories are created automatically.",
                parameters
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Path path;
        try {
            path = Args.path(sandbox, arguments, "path");
        } catch (SecurityException e) {
            log.warn("Rejected file_write: {}", e.getMessage());
            return ToolResult.failure(e.getMessage());
        }

        String content = Args.string(arguments, "content");
        if (content == null) {
            return ToolResult.retryable("Error: 'content' parameter is required");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return ToolResult.failure("Error: content is too large (" + content.length()
                    + " chars, limit is " + MAX_CONTENT_CHARS + "). Write it in smaller parts.");
        }

        boolean append = Args.bool(arguments, "append", false);

        try {
            if (Files.isDirectory(path)) {
                return ToolResult.failure("Error: path is a directory, not a file: " + path);
            }
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            }

            log.info("Wrote {} character(s) to {} (append={})", content.length(), path, append);
            return ToolResult.success("Successfully wrote " + content.length()
                    + " characters to " + path);

        } catch (IOException e) {
            log.error("Failed to write '{}': {}", path, e.getMessage());
            return ToolResult.failure("Error writing file: " + e.getMessage());
        }
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SENSITIVE;
    }

    /**
     * 本工具被限制在其中运行的沙箱。
     */
    public PathSandbox sandbox() {
        return sandbox;
    }
}
