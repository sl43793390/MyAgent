package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
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
 * Built-in tool for editing a file by replacing an exact string with a new one.
 */
public class FileEditTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileEditTool.class);

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
    public String execute(Map<String, Object> arguments) {
        String pathStr = getStringArg(arguments, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' parameter is required";
        }

        String oldStr = getStringArg(arguments, "old_string");
        if (oldStr == null || oldStr.isEmpty()) {
            return "Error: 'old_string' parameter is required and must not be empty";
        }

        String newStr = getStringArg(arguments, "new_string");
        if (newStr == null) {
            return "Error: 'new_string' parameter is required";
        }

        boolean replaceAll = getBoolArg(arguments.get("all"), false);

        Path path = Path.of(pathStr);
        if (!Files.exists(path)) {
            return "Error: file does not exist: " + pathStr;
        }

        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);

            int count = countOccurrences(content, oldStr);
            if (count == 0) {
                return "Error: 'old_string' not found in file: " + pathStr;
            }
            if (count > 1 && !replaceAll) {
                return "Error: 'old_string' occurs " + count + " times in file " + pathStr
                        + ". Provide more surrounding context to make it unique, or set 'all' to true to replace every occurrence.";
            }

            String updated = replaceAll
                    ? content.replace(oldStr, newStr)
                    : content.replaceFirst(Pattern.quote(oldStr), Matcher.quoteReplacement(newStr));

            Files.writeString(path, updated, StandardCharsets.UTF_8);

            log.debug("Replaced {} occurrence(s) in {}", count, pathStr);
            return "Successfully replaced " + (replaceAll ? count : 1) + " occurrence(s) in " + pathStr;
        } catch (IOException e) {
            log.error("Failed to edit file '{}': {}", pathStr, e.getMessage());
            return "Error editing file: " + e.getMessage();
        }
    }

    private int countOccurrences(String content, String target) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(target, index)) != -1) {
            count++;
            index += target.length();
        }
        return count;
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
