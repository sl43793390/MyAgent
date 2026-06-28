package com.agent.core.memory;

import com.agent.core.llm.LLMClient;
import com.agent.core.model.Message;
import com.agent.core.model.Role;
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
    private final long compressionTokenThreshold;
    private LLMClient compressionLLMClient;
    private String compressionPrompt;

    /**
     * Create Redis memory with default settings.
     *
     * @param host Redis host
     * @param port Redis port
     * @param key  Redis key for storing messages
     */
    public RedisMemory(String host, int port, String key) {
        this(host, port, key, 0, 30, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
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
        this(host, port, key, ttlSeconds, 30, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
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
        this(host, port, key, ttlSeconds, maxMessages, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create Redis memory with all parameters.
     *
     * @param host                       Redis host
     * @param port                       Redis port
     * @param key                        Redis key for storing messages
     * @param ttlSeconds                 TTL in seconds (0 for no expiration)
     * @param maxMessages                maximum number of messages to retain
     * @param compressionTokenThreshold  token threshold to trigger compression
     */
    public RedisMemory(String host, int port, String key, int ttlSeconds, int maxMessages,
                       long compressionTokenThreshold) {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(10);
        config.setMaxIdle(5);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);

        this.jedisPool = new JedisPool(config, host, port);
        this.key = key;
        this.ttlSeconds = ttlSeconds;
        this.maxMessages = maxMessages;
        this.compressionTokenThreshold = compressionTokenThreshold;
        this.compressionPrompt = DEFAULT_COMPRESSION_PROMPT;
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
        this(jedisPool, key, ttlSeconds, maxMessages, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create Redis memory with existing JedisPool and compression threshold.
     *
     * @param jedisPool                  existing JedisPool
     * @param key                        Redis key for storing messages
     * @param ttlSeconds                 TTL in seconds (0 for no expiration)
     * @param maxMessages                maximum number of messages to retain
     * @param compressionTokenThreshold  token threshold to trigger compression
     */
    public RedisMemory(JedisPool jedisPool, String key, int ttlSeconds, int maxMessages,
                       long compressionTokenThreshold) {
        this.jedisPool = jedisPool;
        this.key = key;
        this.ttlSeconds = ttlSeconds;
        this.maxMessages = maxMessages;
        this.compressionTokenThreshold = compressionTokenThreshold;
        this.compressionPrompt = DEFAULT_COMPRESSION_PROMPT;
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
        try (Jedis jedis = jedisPool.getResource()) {
            String json = objectMapper.writeValueAsString(message);
            jedis.rpush(key, json);

            // Set TTL if configured
            if (ttlSeconds > 0) {
                jedis.expire(key, ttlSeconds);
            }

            // Trim list if max messages exceeded
            if (maxMessages < 30) {
                jedis.ltrim(key, -maxMessages, -1);
            }

            log.debug("Added message to Redis key '{}', current size: {}", key, size());

            // Auto compress if needed
            autoCompressIfNeeded();

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

    @Override
    public long estimateTokens() {
        List<Message> messages = getMessages();
        long total = 0;
        for (Message msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
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
                try (Jedis jedis = jedisPool.getResource()) {
                    // Re-add system messages
                    for (Message sysMsg : systemMessages) {
                        String json = objectMapper.writeValueAsString(sysMsg);
                        jedis.rpush(key, json);
                    }
                    // Add compressed summary
                    Message summaryMsg = Message.system("[对话摘要]\n" + summary);
                    String json = objectMapper.writeValueAsString(summaryMsg);
                    jedis.rpush(key, json);

                    // Set TTL if configured
                    if (ttlSeconds > 0) {
                        jedis.expire(key, ttlSeconds);
                    }
                }
                log.info("Successfully compressed conversation in Redis");
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

        long currentTokens = estimateTokens();
        if (currentTokens >= compressionTokenThreshold) {
            log.info("Token count {} exceeds threshold {}, triggering compression",
                    currentTokens, compressionTokenThreshold);
            compress();
        }
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
     * Close the JedisPool when done.
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            log.info("Closed Redis connection pool");
        }
    }
}
