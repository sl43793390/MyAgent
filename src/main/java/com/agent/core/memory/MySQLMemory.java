package com.agent.core.memory;

import com.agent.core.model.Message;
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
public class MySQLMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(MySQLMemory.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HikariDataSource dataSource;
    private final String tableName;
    private final String sessionId;
    private final int maxMessages;

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
        this(jdbcUrl, username, password, tableName, sessionId, Integer.MAX_VALUE);
    }

    /**
     * Create MySQL memory with connection details and max messages.
     *
     * @param jdbcUrl     JDBC URL
     * @param username    database username
     * @param password    database password
     * @param tableName   table name for storing messages
     * @param sessionId   session ID for this conversation
     * @param maxMessages maximum number of messages to retain
     */
    public MySQLMemory(String jdbcUrl, String username, String password,
                       String tableName, String sessionId, int maxMessages) {
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
        this.maxMessages = maxMessages;

        // Initialize table if not exists
        initTable();
    }

    /**
     * Create MySQL memory with existing HikariDataSource.
     *
     * @param dataSource  existing HikariDataSource
     * @param tableName   table name for storing messages
     * @param sessionId   session ID for this conversation
     * @param maxMessages maximum number of messages to retain
     */
    public MySQLMemory(HikariDataSource dataSource, String tableName,
                       String sessionId, int maxMessages) {
        this.dataSource = dataSource;
        this.tableName = tableName;
        this.sessionId = sessionId;
        this.maxMessages = maxMessages;

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

            // Trim old messages if max exceeded
            if (maxMessages < Integer.MAX_VALUE) {
                trimOldMessages(conn);
            }

        } catch (SQLException | JsonProcessingException e) {
            log.error("Failed to add message: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add message", e);
        }
    }

    private void trimOldMessages(Connection conn) throws SQLException {
        String trimSQL = """
                DELETE FROM %s
                WHERE session_id = ?
                AND id NOT IN (
                    SELECT id FROM (
                        SELECT id FROM %s
                        WHERE session_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                    ) AS temp
                )
                """.formatted(tableName, tableName);

        try (PreparedStatement pstmt = conn.prepareStatement(trimSQL)) {
            pstmt.setString(1, sessionId);
            pstmt.setString(2, sessionId);
            pstmt.setInt(3, maxMessages);
            pstmt.executeUpdate();
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

                    var role = com.agent.core.model.Role.valueOf(roleStr.toUpperCase());
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
