package com.agent.core.memory;

import com.agent.core.model.Message;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Redis-based implementation of conversation memory.
 * Stores messages in a Redis list with optional TTL.
 */
public class RedisMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(RedisMemory.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final JedisPool jedisPool;
    private final String key;
    private final int ttlSeconds;
    private final int maxMessages;

    /**
     * Create Redis memory with default settings.
     *
     * @param host Redis host
     * @param port Redis port
     * @param key  Redis key for storing messages
     */
    public RedisMemory(String host, int port, String key) {
        this(host, port, key, 0, Integer.MAX_VALUE);
    }

    /**
     * Create Redis memory with TTL.
     *
     * @param host       Redis host
     * @param port       Redis port
     * @param key        Redis key for storing messages
     * @param ttlSeconds TTL in seconds (0 for no expiration)
     */
    public RedisMemory(String host, int port, String key, int ttlSeconds) {
        this(host, port, key, ttlSeconds, Integer.MAX_VALUE);
    }

    /**
     * Create Redis memory with TTL and max messages.
     *
     * @param host        Redis host
     * @param port        Redis port
     * @param key         Redis key for storing messages
     * @param ttlSeconds  TTL in seconds (0 for no expiration)
     * @param maxMessages maximum number of messages to retain
     */
    public RedisMemory(String host, int port, String key, int ttlSeconds, int maxMessages) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);

        this.jedisPool = new JedisPool(config, host, port);
        this.key = key;
        this.ttlSeconds = ttlSeconds;
        this.maxMessages = maxMessages;
    }

    /**
     * Create Redis memory with existing JedisPool.
     *
     * @param jedisPool   existing JedisPool
     * @param key         Redis key for storing messages
     * @param ttlSeconds  TTL in seconds (0 for no expiration)
     * @param maxMessages maximum number of messages to retain
     */
    public RedisMemory(JedisPool jedisPool, String key, int ttlSeconds, int maxMessages) {
        this.jedisPool = jedisPool;
        this.key = key;
        this.ttlSeconds = ttlSeconds;
        this.maxMessages = maxMessages;
    }

    @Override
    public void add(Message message) {
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(message);
            jedis.rpush(key, json);

            // Set TTL if configured
            if (ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }

            // Trim list if max messages exceeded
            if (maxMessages < Integer.MAX_VALUE) {
                jedis.ltrim(key, -maxMessages, -1);
            }

            log.debug("Added message to Redis key '{}', current size: {}", key, size());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize message: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to serialize message", e);
        }
    }

    @Override
    public List<Message> getMessages() {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> jsonList = jedis.lrange(key, 0, -1);
            List<Message> messages = new ArrayList<>();

            for (String json : jsonList) {
                try {
                    Message message = objectMapper.readValue(json, Message.class);
                    messages.add(message);
                } catch (JsonProcessingException e) {
                    log.error("Failed to deserialize message: {}", e.getMessage(), e);
                }
            }

            return Collections.unmodifiableList(messages);
        }
    }

    @Override
    public void clear() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(key);
            log.debug("Cleared Redis key '{}'", key);
        }
    }

    @Override
    public int size() {
        try (Jedis jedis = jedisPool.getResource()) {
            return Math.toIntExact(jedis.llen(key));
        }
    }

    /**
     * Close the JedisPool when done.
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.info("Closed Redis connection pool");
        }
    }
}
