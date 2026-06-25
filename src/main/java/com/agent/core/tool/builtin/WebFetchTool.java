package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Built-in tool for fetching content from a URL.
 */
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.simple(
                "web_fetch",
                "Fetch content from a URL. Returns the text content of the webpage.",
                "url",
                "The URL to fetch content from"
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String urlStr = (String) arguments.get("url");
        if (urlStr == null || urlStr.isBlank()) {
            return "Error: 'url' parameter is required";
        }

        try {
            URL url = URI.create(urlStr).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("User-Agent", "JavaAgent/1.0");

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return "Error: HTTP " + responseCode;
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    content.append(line).append("\n");
                }
            }

            String result = content.toString();
            // Limit response size
            if (result.length() > 10000) {
                result = result.substring(0, 10000) + "\n... [content truncated]";
            }

            log.debug("Fetched {} bytes from {}", result.length(), urlStr);
            return result;

        } catch (Exception e) {
            log.error("Failed to fetch URL '{}': {}", urlStr, e.getMessage());
            return "Error fetching URL: " + e.getMessage();
        }
    }
}
