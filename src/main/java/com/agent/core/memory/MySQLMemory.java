package com.agent.core.memory;

import com.agent.core.llm.LLMClient;
import com.agent.core.model.Message;
import com.agent.core.model.Role;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MySQL-based implementation of conversation memory.
 * Stores messages in a MySQL database table.
 */
public class MySQLMemory implements Memory, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MySQLMemory.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HikariDataSource dataSource;
    private final String tableName;
    private final String sessionId;
    private final long compressionTokenThreshold;
    private LLMClient compressionLLMClient;
    private String compressionPrompt;

    // 增量维护的 token 估算值。-1 表示尚未从 MySQL 加载（懒初始化，支持应用重启后恢复）
    private long currentTokens = -1L;

    /**
     * Create MySQL memory with connection details.
     *
     * @param jdbcUrl   JDBC URL (e.g., jdbc:mysql://localhost:3306/agent)
     * @param username  database username
     * @param password  database password
     * @param tableName table name for storing messages
     * @param sessionId session ID for this conversation
     */
    public MySQLMemory(String jdbcUrl, String username, String password,
                       String tableName, String sessionId) {
        this(jdbcUrl, username, password, tableName, sessionId, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create MySQL memory with connection details and compression threshold.
     *
     * @param jdbcUrl                   JDBC URL
     * @param username                  database username
     * @param password                  database password
     * @param tableName                 table name for storing messages
     * @param sessionId                 session ID for this conversation
     * @param compressionTokenThreshold token threshold to trigger compression
     */
    public MySQLMemory(String jdbcUrl, String username, String password,
                       String tableName, String sessionId, long compressionTokenThreshold) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300000); // 5 minutes
        config.setConnectionTimeout(20000); // 20 seconds
        config.setMaxLifetime(1200000); // 20 minutes

        this.dataSource = new HikariDataSource(config);
        this.tableName = tableName;
        this.sessionId = sessionId;
        this.compressionTokenThreshold = compressionTokenThreshold;
        this.compressionPrompt = DEFAULT_COMPRESSION_PROMPT;

        // Initialize table if not exists
        initTable();
    }

    /**
     * Create MySQL memory with existing HikariDataSource.
     *
     * @param dataSource existing HikariDataSource
     * @param tableName  table name for storing messages
     * @param sessionId  session ID for this conversation
     */
    public MySQLMemory(HikariDataSource dataSource, String tableName, String sessionId) {
        this(dataSource, tableName, sessionId, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create MySQL memory with existing HikariDataSource and compression threshold.
     *
     * @param dataSource                existing HikariDataSource
     * @param tableName                 table name for storing messages
     * @param sessionId                 session ID for this conversation
     * @param compressionTokenThreshold token threshold to trigger compression
     */
    public MySQLMemory(HikariDataSource dataSource, String tableName,
                       String sessionId, long compressionTokenThreshold) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.sessionId = sessionId;
        this.compressionTokenThreshold = compressionTokenThreshold;
        this.compressionPrompt = DEFAULT_COMPRESSION_PROMPT;

        initTable();
    }

    private void initTable() {
        String createTableSQL = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id VARCHAR(255) NOT NULL,
                    role VARCHAR(50) NOT NULL,
                    content TEXT,
                    tool_call_id VARCHAR(255),
                    name VARCHAR(255),
                    tool_calls JSON,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                    INDEX idx_session_id (session_id),
                    INDEX idx_created_at (created_at)
                )
                """.formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
            log.debug("Initialized table '{}'", tableName);
        } catch (SQLException e) {
            log.error("Failed to create table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to create table", e);
        }
    }

    @Override
    public void setCompressionLLMClient(LLMClient llmClient) {
        this.compressionLLMClient = llmClient;
    }

    @Override
    public void setCompressionPrompt(String prompt) {
        this.compressionPrompt = prompt != null ? prompt : DEFAULT_COMPRESSION_PROMPT;
    }

    @Override
    public void add(Message message) {
        String insertSQL = """
                INSERT INTO %s (session_id, role, content, tool_call_id, name, tool_calls)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {

            pstmt.setString(1, sessionId);
            pstmt.setString(2, message.role().getValue());
            pstmt.setString(3, message.content());
            pstmt.setString(4, message.toolCallId());
            pstmt.setString(5, message.name());

            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                pstmt.setString(6, objectMapper.writeValueAsString(message.toolCalls()));
            } else {
                pstmt.setNull(6, Types.VARCHAR);
            }

            pstmt.executeUpdate();
            log.debug("Added message to MySQL table '{}' for session '{}'", tableName, sessionId);

            // 增量更新 token 缓存（避免每次 add 都全量查询数据库计算）
            ensureTokensInitialized();
            currentTokens += estimateMessageTokens(message);

            // Auto compress if needed
            autoCompressIfNeeded();

        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to add message: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add message", e);
        }
    }

    @Override
    public List<Message> getMessages() {
        String selectSQL = """
                SELECT role, content, tool_call_id, name, tool_calls
                FROM %s
                WHERE session_id = ?
                ORDER BY created_at ASC
                """.formatted(tableName);

        List<Message> messages = new ArrayList<>();

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(selectSQL)) {

            pstmt.setString(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String roleStr = rs.getString("role");
                    String content = rs.getString("content");
                    String toolCallId = rs.getString("tool_call_id");
                    String name = rs.getString("name");
                    String toolCallsJson = rs.getString("tool_calls");

                    var role = Role.valueOf(roleStr.toUpperCase());
                    List<com.agent.core.model.ToolCall> toolCalls = null;

                    if (toolCallsJson != null && !toolCallsJson.isBlank()) {
                        toolCalls = objectMapper.readValue(toolCallsJson,
                                objectMapper.getTypeFactory().constructCollectionType(
                                        List.class, com.agent.core.model.ToolCall.class));
                    }

                    messages.add(new Message(role, content, toolCallId, name, toolCalls));
                }
            }

            return Collections.unmodifiableList(messages);

        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to get messages: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get messages", e);
        }
    }

    @Override
    public void clear() {
        String deleteSQL = "DELETE FROM %s WHERE session_id = ?".formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(deleteSQL)) {

            pstmt.setString(1, sessionId);
            int deleted = pstmt.executeUpdate();
            currentTokens = 0L;
            log.debug("Cleared {} messages from MySQL table '{}' for session '{}'",
                    deleted, tableName, sessionId);

        } catch (SQLException e) {
            log.error("Failed to clear messages: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to clear messages", e);
        }
    }

    @Override
    public int size() {
        String countSQL = "SELECT COUNT(*) FROM %s WHERE session_id = ?".formatted(tableName);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(countSQL)) {

            pstmt.setString(1, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }

            return 0;

        } catch (SQLException e) {
            log.error("Failed to get size: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get size", e);
        }
    }

    @Override
    public long estimateTokens() {
        ensureTokensInitialized();
        return currentTokens;
    }

    @Override
    public boolean compress() {
        if (compressionLLMClient == null) {
            log.warn("Compression LLM client not set, cannot compress");
            return false;
        }

        List<Message> messages = getMessages();
        if (messages.isEmpty()) {
            return false;
        }

        // Separate system messages and conversation messages
        List<Message> systemMessages = new ArrayList<>();
        List<Message> conversationMessages = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                systemMessages.add(msg);
            } else {
                conversationMessages.add(msg);
            }
        }

        if (conversationMessages.isEmpty()) {
            return false;
        }

        // Build conversation text for compression
        StringBuilder conversationText = new StringBuilder();
        for (Message msg : conversationMessages) {
            String roleStr = msg.role().getValue();
            String content = msg.content() != null ? msg.content() : "";
            conversationText.append(roleStr).append(": ").append(content).append("\n");
        }

        // Call LLM to compress
        String prompt = compressionPrompt.replace("{conversation}", conversationText.toString());
        List<Message> compressMessages = new ArrayList<>();
        compressMessages.add(Message.system("You are a helpful assistant that compresses conversations."));
        compressMessages.add(Message.user(prompt));

        try {
            var response = compressionLLMClient.chat(compressMessages);
            String summary = response.content();

            if (summary != null && !summary.isBlank()) {
                // Clear and rebuild with compressed summary
                clear();
                try (Connection conn = dataSource.getConnection()) {
                    String insertSQL = """
                            INSERT INTO %s (session_id, role, content, tool_call_id, name, tool_calls)
                            VALUES (?, ?, ?, ?, ?, ?)
                            """.formatted(tableName);

                    // Re-add system messages
                    for (Message sysMsg : systemMessages) {
                        try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                            pstmt.setString(1, sessionId);
                            pstmt.setString(2, sysMsg.role().getValue());
                            pstmt.setString(3, sysMsg.content());
                            pstmt.setString(4, sysMsg.toolCallId());
                            pstmt.setString(5, sysMsg.name());
                            pstmt.setNull(6, Types.VARCHAR);
                            pstmt.executeUpdate();
                        }
                    }

                    // Add compressed summary
                    Message summaryMsg = Message.system("[对话摘要]\n" + summary);
                    try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                        pstmt.setString(1, sessionId);
                        pstmt.setString(2, summaryMsg.role().getValue());
                        pstmt.setString(3, summaryMsg.content());
                        pstmt.setString(4, summaryMsg.toolCallId());
                        pstmt.setString(5, summaryMsg.name());
                        pstmt.setNull(6, Types.VARCHAR);
                        pstmt.executeUpdate();
                    }
                }
                // 压缩后消息列表已重建，重置缓存以触发下次懒加载
                currentTokens = -1L;
                log.info("Successfully compressed conversation in MySQL");
                return true;
            }
        } catch (Exception e) {
            log.error("Failed to compress conversation: {}", e.getMessage(), e);
        }

        return false;
    }

    private void autoCompressIfNeeded() {
        if (compressionLLMClient == null || compressionTokenThreshold <= 0) {
            return;
        }

        ensureTokensInitialized();
        if (currentTokens >= compressionTokenThreshold) {
            log.info("Token count {} exceeds threshold {}, triggering compression",
                    currentTokens, compressionTokenThreshold);
            compress();
        }
    }

    /**
     * 懒初始化 token 缓存。仅在首次访问时从 MySQL 全量加载，之后增量维护。
     * 支持应用重启后从持久化存储恢复 token 计数。
     */
    private void ensureTokensInitialized() {
        if (currentTokens >= 0) {
            return;
        }
        long total = 0;
        for (Message msg : getMessages()) {
            total += estimateMessageTokens(msg);
        }
        currentTokens = total;
    }

    private long estimateMessageTokens(Message message) {
        String content = message.content();
        if (content == null || content.isEmpty()) {
            return 0;
        }

        // Count Chinese characters
        long chineseChars = content.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                .count();
        long otherChars = content.length() - chineseChars;

        // Chinese: ~1.5 tokens per char, English: ~0.25 tokens per char
        return (long) (chineseChars * 1.5 + otherChars * 0.25);
    }

    /**
     * Close the DataSource when done.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("Closed MySQL connection pool");
        }
    }
}
