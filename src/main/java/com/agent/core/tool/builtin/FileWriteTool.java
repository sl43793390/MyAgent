package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * Built-in tool for writing content to a file.
 * Parent directories are created automatically if they do not exist.
 */
public class FileWriteTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileWriteTool.class);

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
    public String execute(Map<String, Object> arguments) {
        String pathStr = getStringArg(arguments, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' parameter is required";
        }

        Object contentObj = arguments.get("content");
        String content = contentObj != null ? contentObj.toString() : null;
        if (content == null) {
            return "Error: 'content' parameter is required";
        }

        boolean append = getBoolArg(arguments.get("append"), false);

        try {
            Path path = Path.of(pathStr);
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            if (append) {
                Files.writeString(path, content, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } else {
                Files.writeString(path, content, StandardCharsets.UTF_8);
            }

            log.debug("Wrote {} characters to {} (append={})", content.length(), pathStr, append);
            return "Successfully wrote " + content.length() + " characters to " + pathStr;
        } catch (IOException e) {
            log.error("Failed to write file '{}': {}", pathStr, e.getMessage());
            return "Error writing file: " + e.getMessage();
        }
    }

    private String getStringArg(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        return value != null ? value.toString() : null;
    }

    private boolean getBoolArg(Object value, boolean defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }
}
