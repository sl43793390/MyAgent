package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Built-in tool for listing files and directories,
 * with optional glob pattern filtering and recursive traversal.
 */
public class FileListTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileListTool.class);

    private static final int MAX_ENTRIES = 500;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "The directory path to list"
                ),
                "pattern", Map.of(
                        "type", "string",
                        "description", "Optional glob pattern to filter entries, e.g. '*.java' or '*.{txt,md}'"
                ),
                "depth", Map.of(
                        "type", "integer",
                        "description", "Maximum traversal depth, 1 for direct children only (optional, default 1)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"path"}
        );

        return new ToolDefinition(
                "file_list",
                "List files and subdirectories in a directory. Directories are suffixed with '/'. "
                        + "Optionally filter entries with a glob pattern and traverse recursively with depth.",
                parameters
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = getStringArg(arguments, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' parameter is required";
        }

        String pattern = getStringArg(arguments, "pattern");
        int depth = parseIntArg(arguments.get("depth"), 1);

        Path dir = Path.of(pathStr);
        if (!Files.isDirectory(dir)) {
            return "Error: not a directory: " + pathStr;
        }

        PathMatcher matcher = (pattern == null || pattern.isBlank())
                ? null
                : FileSystems.getDefault().getPathMatcher("glob:" + pattern);

        List<String> entries = new ArrayList<>();
        boolean truncated = false;

        try (Stream<Path> stream = Files.walk(dir, Math.max(1, depth))) {
            List<Path> paths = stream
                    .filter(p -> !p.equals(dir))
                    .filter(p -> matcher == null || matcher.matches(p.getFileName()) || matcher.matches(p))
                    .sorted()
                    .limit(MAX_ENTRIES + 1)
                    .toList();

            if (paths.size() > MAX_ENTRIES) {
                truncated = true;
                paths = paths.subList(0, MAX_ENTRIES);
            }

            for (Path p : paths) {
                entries.add(describe(p));
            }
        } catch (IOException e) {
            log.error("Failed to list directory '{}': {}", pathStr, e.getMessage());
            return "Error listing directory: " + e.getMessage();
        }

        if (entries.isEmpty()) {
            return "No entries found" + (matcher != null ? " matching pattern '" + pattern + "'" : "") + " in " + pathStr;
        }

        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            sb.append(entry).append("\n");
        }
        if (truncated) {
            sb.append("... [output limited to ").append(MAX_ENTRIES).append(" entries]");
        }

        log.debug("Listed {} entries in {}", entries.size(), pathStr);
        return sb.toString();
    }

    private String describe(Path path) {
        if (Files.isDirectory(path)) {
            return path + "/";
        }
        try {
            return path + " (" + Files.size(path) + " bytes)";
        } catch (IOException e) {
            return path.toString();
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
