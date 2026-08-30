package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Built-in tool for searching a text query inside the files of a directory (grep-like).
 */
public class FileSearchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(FileSearchTool.class);

    private static final int MAX_RESULTS = 100;
    private static final long MAX_FILE_SIZE = 2 * 1024 * 1024; // skip files larger than 2 MB
    private static final int MAX_DEPTH = 10;

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "path", Map.of(
                        "type", "string",
                        "description", "The directory (or single file) to search in"
                ),
                "query", Map.of(
                        "type", "string",
                        "description", "The text to search for"
                ),
                "case_sensitive", Map.of(
                        "type", "boolean",
                        "description", "If true, the search is case-sensitive (optional, default false)"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"path", "query"}
        );

        return new ToolDefinition(
                "file_search",
                "Search for a text query inside all files under a directory (recursive) or within a single file. "
                        + "Returns matching lines in 'path:lineNumber: content' format.",
                parameters
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String pathStr = getStringArg(arguments, "path");
        if (pathStr == null || pathStr.isBlank()) {
            return "Error: 'path' parameter is required";
        }

        String query = getStringArg(arguments, "query");
        if (query == null || query.isEmpty()) {
            return "Error: 'query' parameter is required and must not be empty";
        }

        boolean caseSensitive = getBoolArg(arguments.get("case_sensitive"), false);

        Path root = Path.of(pathStr);
        if (!Files.exists(root)) {
            return "Error: path does not exist: " + pathStr;
        }

        String searchTarget = caseSensitive ? query : query.toLowerCase(Locale.ROOT);

        List<String> results = new ArrayList<>();
        int totalMatches = 0;

        try (Stream<Path> stream = Files.exists(root) && Files.isDirectory(root)
                ? Files.walk(root, MAX_DEPTH)
                : Stream.of(root)) {

            List<Path> files = stream.filter(Files::isRegularFile).toList();

            for (Path file : files) {
                try {
                    if (Files.size(file) > MAX_FILE_SIZE) {
                        continue;
                    }
                    List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        String haystack = caseSensitive ? line : line.toLowerCase(Locale.ROOT);
                        if (haystack.contains(searchTarget)) {
                            totalMatches++;
                            if (results.size() < MAX_RESULTS) {
                                results.add(file + ":" + (i + 1) + ": " + line);
                            }
                        }
                    }
                } catch (IOException e) {
                    // Skip unreadable or non-text files
                }
            }
        } catch (IOException e) {
            log.error("Failed to search in '{}': {}", pathStr, e.getMessage());
            return "Error searching files: " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No matches found for '" + query + "' in " + pathStr;
        }

        StringBuilder sb = new StringBuilder();
        for (String result : results) {
            sb.append(result).append("\n");
        }
        if (totalMatches > MAX_RESULTS) {
            sb.append("... [showing first ").append(MAX_RESULTS)
                    .append(" of ").append(totalMatches).append(" matches]");
        }

        log.debug("Found {} matches for '{}' in {}", totalMatches, query, pathStr);
        return sb.toString();
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
