package com.agent.core.tool.builtin;

import com.agent.core.security.UrlGuard;
import com.agent.core.tool.RiskLevel;
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 用于从 URL 获取内容的内置工具。
 *
 * <p>URL 来自大语言模型（LLM），也就是来自不可信的输入。在建立任何连接之前，URL 都会先经过
 * {@link UrlGuard} 校验，以阻断服务端请求伪造（SSRF）：非 http(s) 协议（尤其是 {@code file://}）、
 * 内嵌的凭据，以及解析到环回、私有或链路本地地址的主机都会被拒绝。重定向也会用同一道防护重新校验，
 * 而非盲目跟随。
 */
public class WebFetchTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(WebFetchTool.class);

    private static final int MAX_CONTENT_CHARS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_BODY_BYTES = 1_000_000;

    private final UrlGuard guard;

    /** 使用安全默认值获取：仅允许公网地址，最多跟随 3 次重定向。 */
    public WebFetchTool() {
        this(UrlGuard.defaults());
    }

    /**
     * @param guard 出站的 URL 策略；为 null 时使用 {@link UrlGuard#defaults()}
     */
    public WebFetchTool(UrlGuard guard) {
        this.guard = guard != null ? guard : UrlGuard.defaults();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.simple(
                "web_fetch",
                "Fetch content from an http(s) URL. Returns the text content of the webpage.",
                "url",
                "The URL to fetch content from"
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object urlObj = arguments.get("url");
        String urlStr = urlObj != null ? urlObj.toString() : null;
        if (urlStr == null || urlStr.isBlank()) {
            return ToolResult.retryable("Error: 'url' parameter is required");
        }

        URL url;
        try {
            url = guard.validate(urlStr);
        } catch (SecurityException e) {
            log.warn("Rejected web_fetch target: {}", e.getMessage());
            return ToolResult.failure(e.getMessage());
        }

        try {
            String result = fetchFollowingRedirects(url, 0);
            log.debug("Fetched {} chars from {}", result.length(), urlStr);
            return ToolResult.success(result);
        } catch (IOException e) {
            log.error("Failed to fetch URL '{}': {}", urlStr, e.getMessage());
            return ToolResult.retryable("Error fetching URL: " + e.getMessage());
        }
    }

    /**
     * 跟随有限次数的重定向，并在每一跳重新校验，使得一个公网 URL 无法用
     * {@code 302} 跳转到内网地址从而绕过防护。
     */
    private String fetchFollowingRedirects(URL url, int redirectsSeen) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false); // 每一跳都由我们自行重新校验
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "JavaAgent/1.0");

            int responseCode = connection.getResponseCode();

            if (isRedirect(responseCode)) {
                String location = connection.getHeaderField("Location");
                if (location == null) {
                    return "Error: HTTP " + responseCode + " redirect without a 'Location' header";
                }
                if (redirectsSeen >= guard.maxRedirects()) {
                    return "Error: too many redirects (limit " + guard.maxRedirects() + ")";
                }
                URL next;
                try {
                    next = guard.validate(connection.getURL().toURI().resolve(location).toString());
                } catch (Exception e) {
                    return "Error: blocked or malformed redirect target: " + e.getMessage();
                }
                return fetchFollowingRedirects(next, redirectsSeen + 1);
            }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                return "Error: HTTP " + responseCode;
            }

            StringBuilder content = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buffer = new char[8192];
                int read;
                int totalChars = 0;
                while ((read = reader.read(buffer)) != -1) {
                    content.append(buffer, 0, read);
                    totalChars += read;
                    if (totalChars >= MAX_BODY_BYTES) {
                        break;
                    }
                }
            }

            String result = content.toString();
            if (result.length() > MAX_CONTENT_CHARS) {
                result = result.substring(0, MAX_CONTENT_CHARS)
                        + "\n... [content truncated, " + result.length() + " chars total]";
            }
            return result;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isRedirect(int responseCode) {
        return responseCode == HttpURLConnection.HTTP_MOVED_PERM
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307
                || responseCode == 308;
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SENSITIVE;
    }

    /**
     * 本工具被限制在其中运行的出站 URL 策略。
     */
    public UrlGuard guard() {
        return guard;
    }
}
