package com.agent.core.memory;

import com.agent.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.Pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于 Redis 的会话存储，每个会话键对应一个列表，并可设置可选的 TTL。
 *
 * <h3>连接池</h3>
 * <p>通过 host/port 构造时，{@link JedisPool} 会在所有指向同一 Redis 端点的
 * {@code RedisMemory} 实例之间<b>共享并采用引用计数</b>。此前的行为是每个会话一个连接池——
 * 1,000 个会话就会打开 1,000 个连接池、多达 10,000 个套接字，没有服务器能承受
 * （而且永远不会被关闭，因为 {@code RedisMemory} 是由工厂创建的，智能体从未持有它）。
 *
 * <p>若传入的是外部创建的 {@link JedisPool}，则本实例<b>不</b>拥有它，
 * {@link #close()} 也不会去关闭它。
 */
public class RedisMemory extends AbstractMemory implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisMemory.class);

    private static final Map<String, PoolRef> POOLS = new ConcurrentHashMap<>();
    private static final Object POOL_LOCK = new Object();

    private final JedisPool jedisPool;
    private final String key;
    private final int ttlSeconds;
    private final boolean ownsPool;
    private final String poolKey;

    /**
     * 使用默认设置创建 Redis 存储。
     *
     * @param host Redis 主机地址
     * @param port Redis 端口
     * @param key  用于存储消息的 Redis 键
     */
    public RedisMemory(String host, int port, String key) {
        this(host, port, key, 0, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 创建带 TTL 的 Redis 存储。
     *
     * @param host       Redis 主机地址
     * @param port       Redis 端口
     * @param key        用于存储消息的 Redis 键
     * @param ttlSeconds TTL（秒）；为 0 表示永不过期
     */
    public RedisMemory(String host, int port, String key, int ttlSeconds) {
        this(host, port, key, ttlSeconds, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 创建带 TTL 与压缩阈值的 Redis 存储。
     */
    public RedisMemory(String host, int port, String key, int ttlSeconds,
                       long compressionTokenThreshold) {
        super(compressionTokenThreshold);
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Redis key must not be blank");
        }
        this.poolKey = host + ":" + port;
        this.jedisPool = acquirePool(host, port);
        this.ownsPool = true;
        this.key = key;
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    /**
     * 使用已有的 {@link JedisPool} 创建 Redis 存储。本实例不拥有该连接池，
     * {@link #close()} 也不会关闭它。
     */
    public RedisMemory(JedisPool jedisPool, String key, int ttlSeconds) {
        this(jedisPool, key, ttlSeconds, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 使用已有的 {@link JedisPool} 以及指定的压缩阈值创建 Redis 存储。
     */
    public RedisMemory(JedisPool jedisPool, String key, int ttlSeconds,
                       long compressionTokenThreshold) {
        super(compressionTokenThreshold);
        if (jedisPool == null) {
            throw new IllegalArgumentException("JedisPool must not be null");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Redis key must not be blank");
        }
        this.jedisPool = jedisPool;
        this.poolKey = null;
        this.ownsPool = false;
        this.key = key;
        this.ttlSeconds = Math.max(0, ttlSeconds);
    }

    /**
     * 本会话对应的 Redis 键。
     */
    public String key() {
        return key;
    }

    @Override
    protected void doAdd(Message message) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = JsonSupport.write(message);
            Pipeline pipeline = jedis.pipelined();
            pipeline.rpush(key, json);
            if (ttlSeconds > 0) {
                pipeline.expire(key, ttlSeconds);
            }
            pipeline.sync();
            log.debug("Added message to Redis key '{}'", key);
        } catch (Exception e) {
            log.error("Failed to add message to Redis key '{}': {}", key, e.getMessage(), e);
            throw new RuntimeException("Failed to add message to Redis", e);
        }
    }

    @Override
    protected List<Message> doGetMessages() {
        List<Message> messages = new ArrayList<>();
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> serialized = jedis.lrange(key, 0, -1);
            for (String json : serialized) {
                try {
                    Message message = JsonSupport.read(json);
                    if (message != null) {
                        messages.add(message);
                    }
                } catch (Exception e) {
                    // 单条损坏的记录不应导致整个历史记录都无法读取。
                    log.error("Skipping unreadable message in Redis key '{}': {}", key, e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.error("Failed to read messages from Redis key '{}': {}", key, e.getMessage(), e);
            throw e;
        }
        return messages;
    }

    @Override
    protected void doClear() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            log.debug("Cleared Redis key '{}'", key);
        } catch (RuntimeException e) {
            log.error("Failed to clear Redis key '{}': {}", key, e.getMessage(), e);
            throw e;
        }
    }

    @Override
    protected int doSize() {
        try (Jedis jedis = jedisPool.getResource()) {
            return Math.toIntExact(jedis.llen(key));
        } catch (RuntimeException e) {
            log.error("Failed to count messages in Redis key '{}': {}", key, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 在一次流水线往返中整体替换历史，使并发读取者要么看到旧历史、要么看到新历史，
     * 绝不会看到两者的混合片段。
     */
    @Override
    protected void doReplaceAll(List<Message> replacement) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> serialized = new ArrayList<>(replacement.size());
            for (Message message : replacement) {
                serialized.add(JsonSupport.write(message));
            }

            Pipeline pipeline = jedis.pipelined();
            pipeline.del(key);
            for (String json : serialized) {
                pipeline.rpush(key, json);
            }
            if (ttlSeconds > 0) {
                pipeline.expire(key, ttlSeconds);
            }
            pipeline.sync();
        } catch (Exception e) {
            log.error("Failed to replace messages in Redis key '{}': {}", key, e.getMessage(), e);
            throw new RuntimeException("Failed to replace messages in Redis", e);
        }
    }

    /**
     * 若本实例持有共享连接池的引用，则释放该引用。外部传入的连接池绝不会在此关闭。
     */
    @Override
    public void close() {
        if (ownsPool && poolKey != null) {
            releasePool(poolKey);
        }
    }

    private static JedisPool acquirePool(String host, int port) {
        synchronized (POOL_LOCK) {
            PoolRef reference = POOLS.get(poolKeyFor(host, port));
            if (reference == null) {
                reference = new PoolRef(newPool(host, port));
                POOLS.put(poolKeyFor(host, port), reference);
            }
            reference.references++;
            return reference.pool;
        }
    }

    private static void releasePool(String poolKey) {
        synchronized (POOL_LOCK) {
            PoolRef reference = POOLS.get(poolKey);
            if (reference == null) {
                return;
            }
            reference.references--;
            if (reference.references <= 0) {
                POOLS.remove(poolKey);
                closeQuietly(reference.pool);
            }
        }
    }

    private static JedisPool newPool(String host, int port) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(32);
        config.setMaxIdle(8);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        config.setBlockWhenExhausted(true);
        log.info("Creating shared Redis pool for {}:{}", host, port);
        return new JedisPool(config, host, port);
    }

    private static void closeQuietly(JedisPool pool) {
        try {
            if (!pool.isClosed()) {
                pool.close();
                log.info("Closed shared Redis pool");
            }
        } catch (RuntimeException e) {
            log.warn("Failed to close Redis pool: {}", e.getMessage());
        }
    }

    private static String poolKeyFor(String host, int port) {
        return host + ":" + port;
    }

    private static final class PoolRef {
        private final JedisPool pool;
        private int references;

        private PoolRef(JedisPool pool) {
            this.pool = pool;
        }
    }
}
