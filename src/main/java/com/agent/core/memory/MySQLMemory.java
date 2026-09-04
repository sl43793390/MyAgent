package com.agent.core.memory;

import com.agent.core.model.Message;
import com.agent.core.model.Role;
import com.agent.core.model.ToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 基于 MySQL 的会话存储：每条消息一行，按会话 ID 划分作用域。
 *
 * <h3>改了什么，为什么</h3>
 * <ol>
 *   <li><b>连接池共享并按引用计数。</b> 每个会话各自创建一个连接池，意味着 1,000 个会话会对
 *       一台默认仅接受 151 个连接的服务器打开 1,000 个 HikariCP 连接池——而且从来没有人关闭它们。
 *       共用同一 JDBC URL 和用户的会话现在共享同一个连接池；当最后一个引用被释放时该连接池才会关闭。</li>
 *   <li><b>消息排序是全局有序的。</b> 旧表结构使用
 *       {@code created_at TIMESTAMP}，MySQL 以<b>秒</b>级精度存储它。一个智能体循环会在同一秒内写入
 *       多条消息，因此 {@code ORDER BY created_at ASC} 会以未定义的顺序返回它们——工具结果可能排在其
 *       请求它的助手消息之前，而服务商将拒绝该会话。该列现在是
 *       {@code TIMESTAMP(6)}，排序回退到自增的 {@code id}，它永远不会出现平局。</li>
 *   <li><b>建表在每个 JVM 内只发生一次</b>，并且也作为
 *       {@link #ensureTable(javax.sql.DataSource, String)} 提供，供希望通过显式迁移而非隐式 DDL 的应用使用。</li>
 *   <li><b>表名经过校验。</b> 它不能是绑定参数，因此被插值进 SQL；只接受普通标识符，从而关闭了
 *       构造参数中的一个 SQL 注入入口。</li>
 * </ol>
 */
public class MySQLMemory extends AbstractMemory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MySQLMemory.class);

    /** 表名会被插值进 SQL，因此只接受普通标识符。 */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,63}");

    private static final Map<String, PoolRef> POOLS = new ConcurrentHashMap<>();
    private static final Object POOL_LOCK = new Object();
    private static final Set<String> INITIALIZED_TABLES = ConcurrentHashMap.newKeySet();

    private final HikariDataSource dataSource;
    private final String tableName;
    private final String sessionId;
    private final boolean ownsPool;
    private final String poolKey;

    /**
     * 使用连接信息创建 MySQL 存储。
     *
     * @param jdbcUrl   JDBC URL（例如 {@code jdbc:mysql://localhost:3306/agent}）
     * @param username  数据库用户名
     * @param password  数据库密码
     * @param tableName 用于存储消息的表名
     * @param sessionId 本次会话的会话 ID
     */
    public MySQLMemory(String jdbcUrl, String username, String password,
                       String tableName, String sessionId) {
        this(jdbcUrl, username, password, tableName, sessionId, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 使用连接信息和压缩阈值创建 MySQL 存储。
     */
    public MySQLMemory(String jdbcUrl, String username, String password,
                       String tableName, String sessionId, long compressionTokenThreshold) {
        super(compressionTokenThreshold);
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("jdbcUrl must not be blank");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.tableName = validateTableName(tableName);
        this.sessionId = sessionId;
        this.poolKey = poolKeyFor(jdbcUrl, username);
        this.dataSource = acquirePool(jdbcUrl, username, password);
        this.ownsPool = true;
        try {
            ensureTable(dataSource, tableName);
        } catch (RuntimeException e) {
            // 在取得连接池引用之后构造失败：释放它，以免表错误泄漏一个会永久保持共享连接池打开的引用。
            releasePool(poolKey);
            throw e;
        }
    }

    /**
     * 使用已有的 {@link HikariDataSource} 创建 MySQL 存储。该连接池不由本实例拥有，
     * {@link #close()} 也不会关闭它。
     */
    public MySQLMemory(HikariDataSource dataSource, String tableName, String sessionId) {
        this(dataSource, tableName, sessionId, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 使用已有的 {@link HikariDataSource} 和压缩阈值创建 MySQL 存储。
     */
    public MySQLMemory(HikariDataSource dataSource, String tableName,
                       String sessionId, long compressionTokenThreshold) {
        super(compressionTokenThreshold);
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        this.dataSource = dataSource;
        this.tableName = validateTableName(tableName);
        this.sessionId = sessionId;
        this.poolKey = null;
        this.ownsPool = false;
        ensureTable(dataSource, tableName);
    }

    /**
     * 支撑本存储的表。
     */
    public String tableName() {
        return tableName;
    }

    /**
     * 本存储所绑定的会话。
     */
    public String sessionId() {
        return sessionId;
    }

    // ---------------------------------------------------------------- 表结构

    /**
     * 若消息表不存在则创建它。可重复调用：对于每个（数据源，表）组合，DDL 最多执行一次。
     *
     * <p>使用迁移工具管理表结构的应用应在启动时调用一次，并依靠每 JVM 的守卫使后续调用无额外开销。
     *
     * @param dataSource 用于执行 DDL 的数据源
     * @param tableName  表名；必须是普通标识符
     */
    public static void ensureTable(javax.sql.DataSource dataSource, String tableName) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource must not be null");
        }
        String table = validateTableName(tableName);
        String key = System.identityHashCode(dataSource) + "|" + table.toLowerCase(Locale.ROOT);
        if (!INITIALIZED_TABLES.add(key)) {
            return;
        }

        // created_at 特意使用 TIMESTAMP(6)：秒级精度不足以排列单个智能体循环写入的消息，
        // 而 id 是永远不会出现平局的决胜列。
        String ddl = """
                CREATE TABLE IF NOT EXISTS %s (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    session_id VARCHAR(255) NOT NULL,
                    role VARCHAR(50) NOT NULL,
                    content TEXT,
                    tool_call_id VARCHAR(255),
                    name VARCHAR(255),
                    tool_calls JSON,
                    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                    INDEX idx_session_id (session_id),
                    INDEX idx_session_created (session_id, created_at)
                )
                """.formatted(table);

        try {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            }
            log.debug("Ensured message table '{}' exists", table);
        } catch (SQLException e) {
            INITIALIZED_TABLES.remove(key);
            log.error("Failed to create table '{}': {}", table, e.getMessage(), e);
            throw new RuntimeException("Failed to create table '" + table + "'", e);
        }
    }

    private static String validateTableName(String tableName) {
        if (tableName == null || !SAFE_TABLE_NAME.matcher(tableName).matches()) {
            throw new IllegalArgumentException(
                    "Invalid table name '" + tableName + "': expected a plain SQL identifier");
        }
        return tableName;
    }

    // ---------------------------------------------------------------- 存储原语

    @Override
    protected void doAdd(Message message) {
        String sql = insertSql();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            bindMessage(statement, message);
            statement.executeUpdate();
            log.debug("Added message to table '{}' for session '{}'", tableName, sessionId);
        } catch (Exception e) {
            log.error("Failed to add message to table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to add message", e);
        }
    }

    @Override
    protected List<Message> doGetMessages() {
        String sql = """
                SELECT role, content, tool_call_id, name, tool_calls
                FROM %s
                WHERE session_id = ?
                ORDER BY created_at ASC, id ASC
                """.formatted(tableName);

        List<Message> messages = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    messages.add(readMessage(resultSet));
                }
            }
            return messages;

        } catch (SQLException e) {
            log.error("Failed to read messages from table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to get messages", e);
        }
    }

    @Override
    protected void doClear() {
        String sql = "DELETE FROM %s WHERE session_id = ?".formatted(tableName);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            int deleted = statement.executeUpdate();
            log.debug("Deleted {} message(s) from table '{}' for session '{}'",
                    deleted, tableName, sessionId);
        } catch (SQLException e) {
            log.error("Failed to clear messages in table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to clear messages", e);
        }
    }

    @Override
    protected int doSize() {
        String sql = "SELECT COUNT(*) FROM %s WHERE session_id = ?".formatted(tableName);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt(1) : 0;
            }
        } catch (SQLException e) {
            log.error("Failed to count messages in table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to get size", e);
        }
    }

    /**
     * 在单个事务中整体替换历史，这样即便失败，旧历史也保持完整，而不会出现删了一半的情况。
     */
    @Override
    protected void doReplaceAll(List<Message> replacement) {
        String deleteSql = "DELETE FROM %s WHERE session_id = ?".formatted(tableName);
        String insertSql = insertSql();

        try (Connection connection = dataSource.getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(deleteSql)) {
                    delete.setString(1, sessionId);
                    delete.executeUpdate();
                }
                for (Message message : replacement) {
                    try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                        bindMessage(insert, message);
                        insert.executeUpdate();
                    }
                }
                connection.commit();
            } catch (Exception e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (Exception e) {
            log.error("Failed to replace messages in table '{}': {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to replace messages", e);
        }
    }

    // ---------------------------------------------------------------- 生命周期

    /**
     * 如果本实例持有该共享连接池的引用则释放它。外部传入的 {@link HikariDataSource} 不会在此处被关闭。
     */
    @Override
    public void close() {
        if (ownsPool && poolKey != null) {
            releasePool(poolKey);
        }
    }

    // ---------------------------------------------------------------- 内部实现

    private String insertSql() {
        return """
                INSERT INTO %s (session_id, role, content, tool_call_id, name, tool_calls)
                VALUES (?, ?, ?, ?, ?, ?)
                """.formatted(tableName);
    }

    private void bindMessage(PreparedStatement statement, Message message) throws Exception {
        statement.setString(1, sessionId);
        statement.setString(2, message.role().getValue());
        statement.setString(3, message.content());
        statement.setString(4, message.toolCallId());
        statement.setString(5, message.name());
        if (message.hasToolCalls()) {
            statement.setString(6, JsonSupport.MAPPER.writeValueAsString(message.toolCalls()));
        } else {
            statement.setNull(6, Types.VARCHAR);
        }
    }

    private Message readMessage(ResultSet resultSet) throws SQLException {
        String roleValue = resultSet.getString("role");
        Role role;
        try {
            role = Role.valueOf(roleValue.toUpperCase(Locale.ROOT));
        } catch (RuntimeException e) {
            throw new SQLException("Unknown message role '" + roleValue + "'", e);
        }

        List<ToolCall> toolCalls = null;
        String toolCallsJson = resultSet.getString("tool_calls");
        if (toolCallsJson != null && !toolCallsJson.isBlank()) {
            try {
                toolCalls = JsonSupport.MAPPER.readValue(toolCallsJson, new TypeReference<List<ToolCall>>() {});
            } catch (Exception e) {
                // JSON 列可能存放手工编辑或旧版的负载；在此选择降级而非让整段会话无法读取。
                log.error("Failed to deserialize tool calls: {}", e.getMessage());
            }
        }

        return new Message(
                role,
                resultSet.getString("content"),
                resultSet.getString("tool_call_id"),
                resultSet.getString("name"),
                toolCalls);
    }

    private static HikariDataSource acquirePool(String jdbcUrl, String username, String password) {
        String key = poolKeyFor(jdbcUrl, username);
        synchronized (POOL_LOCK) {
            PoolRef reference = POOLS.get(key);
            if (reference == null) {
                reference = new PoolRef(newPool(jdbcUrl, username, password, key));
                POOLS.put(key, reference);
            }
            reference.references++;
            return reference.dataSource;
        }
    }

    private static void releasePool(String key) {
        synchronized (POOL_LOCK) {
            PoolRef reference = POOLS.get(key);
            if (reference == null) {
                return;
            }
            reference.references--;
            if (reference.references <= 0) {
                POOLS.remove(key);
                closeQuietly(reference.dataSource);
            }
        }
    }

    /**
     * 单个共享连接池按整个应用而非每个会话来规划大小。10 个连接足以满足典型的智能体负载，
     * 因为每条语句只短暂借用一个连接。
     */
    private static HikariDataSource newPool(String jdbcUrl, String username, String password,
                                            String poolName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(300_000);      // 5 分钟
        config.setConnectionTimeout(20_000); // 20 秒
        config.setMaxLifetime(1_200_000);    // 20 分钟
        config.setPoolName("agent-mysql-" + sanitizePoolName(poolName));
        log.info("Creating shared MySQL pool '{}'", config.getPoolName());
        return new HikariDataSource(config);
    }

    private static void closeQuietly(HikariDataSource dataSource) {
        try {
            if (!dataSource.isClosed()) {
                dataSource.close();
                log.info("Closed shared MySQL pool");
            }
        } catch (RuntimeException e) {
            log.warn("Failed to close MySQL pool: {}", e.getMessage());
        }
    }

    private static String poolKeyFor(String jdbcUrl, String username) {
        return jdbcUrl + "|" + username;
    }

    private static String sanitizePoolName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static final class PoolRef {
        private final HikariDataSource dataSource;
        private int references;

        private PoolRef(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }
    }
}
