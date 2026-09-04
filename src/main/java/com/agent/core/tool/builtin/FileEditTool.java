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
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 替换文件中一段精确匹配的字符串。
 *
 * <p>要求 {@code old_string} 唯一，正是这一约束让由模型驱动的编辑变得安全：模型必须引用它打算修改的
 * 文本，而不能「整文件重写」（那样会让幻觉悄无声息地删除内容）。如果引用匹配到多处，编辑会被拒绝，
 * 并提示模型补充上下文——从而让歧义永远不会演变成错误的修改。
 */
public class FileEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);

    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;

    private final PathSandbox sandbox;

    /** 在进程当前工作目录范围内编辑文件。 */
    public FileEditTool() {
        this(PathSandbox.currentDirectory());
    }

    /**
     * @param sandbox 本工具允许编辑的目录边界
     */
    public FileEditTool(PathSandbox sandbox) {
        this.sandbox = sandbox != null ? sandbox : PathSandbox.currentDirectory();
    }

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "The file path to edit"
                ),
                "old_string", Map.of(
                        "type", "string",
                        "description", "The exact text to search for in the file"
                ),
                "new_string", Map.of(
                        "type", "string",
                        "description", "The text to replace it with"
                ),
                "all", Map.of(
                        "type", "boolean",
                        "description", "If true, replace every occurrence (optional, default false: replace only a unique occurrence)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"path", "old_string", "new_string"}
        );

        return new ToolDefinition(
                "file_edit",
                "Edit a file by replacing an exact text ('old_string') with new text ('new_string'). "
                        + "By default the old_string must occur exactly once in the file; "
                        + "include enough surrounding context to make it unique, or set 'all' to true to replace every occurrence.",
                parameters
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Path path;
        try {
            path = Args.path(sandbox, arguments, "path");
        } catch (SecurityException e) {
            log.warn("Rejected file_edit: {}", e.getMessage());
            return ToolResult.failure(e.getMessage());
        }

        String oldStr = Args.string(arguments, "old_string");
        if (oldStr == null) {
            return ToolResult.retryable("Error: 'old_string' parameter is required and must not be empty");
        }
        String newStr = Args.string(arguments, "new_string");
        if (newStr == null) {
            return ToolResult.retryable("Error: 'new_string' parameter is required");
        }

        boolean replaceAll = Args.bool(arguments, "all", false);

        if (!Files.exists(path)) {
            return ToolResult.failure("Error: file does not exist: " + path);
        }
        if (Files.isDirectory(path)) {
            return ToolResult.failure("Error: path is a directory, not a file: " + path);
        }

        try {
            long size = Files.size(path);
            if (size > MAX_FILE_BYTES) {
                return ToolResult.failure("Error: file is too large to edit ("
                        + size + " bytes, limit is " + MAX_FILE_BYTES + ")");
            }

            String content = Files.readString(path, StandardCharsets.UTF_8);

            int occurrences = countOccurrences(content, oldStr);
            if (occurrences == 0) {
                return ToolResult.retryable("Error: 'old_string' not found in " + path
                        + ". Read the file again and quote the text exactly.");
            }
            if (occurrences > 1 && !replaceAll) {
                return ToolResult.retryable("Error: 'old_string' occurs " + occurrences
                        + " times in " + path
                        + ". Provide more surrounding context to make it unique, "
                        + "or set 'all' to true to replace every occurrence.");
            }

            String updated = replaceAll
                    ? content.replace(oldStr, newStr)
                    : content.replaceFirst(Pattern.quote(oldStr), Matcher.quoteReplacement(newStr));

            // 先写入同一目录下的临时文件，再将其移动到位，这样即使写入中途被打断，
            // 也不会留下只写了一半的文件。
            Path directory = path.getParent();
            Path temp = Files.createTempFile(directory, ".agent-edit-", ".tmp");
            try {
                Files.writeString(temp, updated, StandardCharsets.UTF_8);
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }

            log.info("Replaced {} occurrence(s) in {}", replaceAll ? occurrences : 1, path);
            return ToolResult.success("Successfully replaced "
                    + (replaceAll ? occurrences : 1) + " occurrence(s) in " + path);

        } catch (IOException e) {
            log.error("Failed to edit '{}': {}", path, e.getMessage());
            return ToolResult.failure("Error editing file: " + e.getMessage());
        }
    }

    private static int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
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
