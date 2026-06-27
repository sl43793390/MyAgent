package com.agent.core.memory;

import com.agent.core.llm.LLMClient;
import com.agent.core.model.Message;
import com.agent.core.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory implementation of conversation memory.
 */
public class InMemoryStore implements Memory {

    private static final Logger log = LoggerFactory.getLogger(InMemoryStore.class);

    private final List<Message> messages = new ArrayList<>();
    private final int maxMessages;
    private final long compressionTokenThreshold;
    private LLMClient compressionLLMClient;
    private String compressionPrompt;

    /**
     * Create memory with unlimited capacity.
     */
    public InMemoryStore() {
        this(30, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create memory with a maximum number of messages to retain.
     *
     * @param maxMessages maximum number of messages to retain
     */
    public InMemoryStore(int maxMessages) {
        this(maxMessages, DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create memory with max messages and compression threshold.
     *
     * @param maxMessages                  maximum number of messages to retain
     * @param compressionTokenThreshold    token threshold to trigger compression
     */
    public InMemoryStore(int maxMessages, long compressionTokenThreshold) {
        this.maxMessages = maxMessages;
        this.compressionTokenThreshold = compressionTokenThreshold;
        this.compressionPrompt = DEFAULT_COMPRESSION_PROMPT;
    }

    /**
     * Set the LLM client for compression.
     */
    @Override
    public void setCompressionLLMClient(LLMClient llmClient) {
        this.compressionLLMClient = llmClient;
    }

    /**
     * Set custom compression prompt.
     */
    @Override
    public void setCompressionPrompt(String prompt) {
        this.compressionPrompt = prompt != null ? prompt : DEFAULT_COMPRESSION_PROMPT;
    }

    @Override
    public synchronized void add(Message message) {
        messages.add(message);
        trimIfNeeded();
        autoCompressIfNeeded();
    }

    @Override
    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    @Override
    public synchronized int size() {
        return messages.size();
    }

    @Override
    public synchronized long estimateTokens() {
        long total = 0;
        for (Message msg : messages) {
            total += estimateMessageTokens(msg);
        }
        return total;
    }

    @Override
    public synchronized boolean compress() {
        if (compressionLLMClient == null) {
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
                // Replace all conversation messages with a single summary
                messages.clear();
                messages.addAll(systemMessages);
                messages.add(Message.system("[对话摘要]\n" + summary));
                return true;
            }
        } catch (Exception e) {
            // Log error but don't throw
            log.error("Failed to compress conversation: {}", e.getMessage(), e);
        }

        return false;
    }

    private void trimIfNeeded() {
        if (messages.size() <= maxMessages) {
            return;
        }

        // Keep system messages and trim non-system messages from the beginning
        List<Message> systemMessages = new ArrayList<>();
        List<Message> nonSystemMessages = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == Role.SYSTEM) {
                systemMessages.add(msg);
            } else {
                nonSystemMessages.add(msg);
            }
        }

        // Remove oldest non-system messages
        int excess = nonSystemMessages.size() - (maxMessages - systemMessages.size());
        if (excess > 0) {
            nonSystemMessages = nonSystemMessages.subList(excess, nonSystemMessages.size());
        }

        messages.clear();
        messages.addAll(systemMessages);
        messages.addAll(nonSystemMessages);
    }

    private void autoCompressIfNeeded() {
        if (compressionLLMClient == null || compressionTokenThreshold <= 0) {
            return;
        }

        long currentTokens = estimateTokens();
        if (currentTokens >= compressionTokenThreshold) {
            compress();
        }
    }

    private long estimateMessageTokens(Message message) {
        // Rough estimation: 1 token ≈ 4 characters for English, 1.5 for Chinese
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
}
