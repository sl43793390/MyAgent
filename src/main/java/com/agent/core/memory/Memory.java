package com.agent.core.memory;

import com.agent.core.llm.LLMClient;
import com.agent.core.model.Message;

import java.util.List;

/**
 * Interface for managing conversation memory.
 */
public interface Memory {

    /**
     * Default token threshold for auto-compression (10,0000 tokens).
     */
    long DEFAULT_COMPRESSION_TOKEN_THRESHOLD = 100000L;

    /**
     * Default compression prompt template.
     */
    String DEFAULT_COMPRESSION_PROMPT = """
            Please compress the following conversation into a concise summary that preserves key information,
            decisions, and context. The summary should be brief but comprehensive enough to continue the conversation.
            
            Conversation:
            {conversation}
            
            Provide a compressed summary:
            """;

    /**
     * Add a message to memory.
     */
    void add(Message message);

    /**
     * Get all messages in memory.
     */
    List<Message> getMessages();

    /**
     * Clear all messages from memory.
     */
    void clear();

    /**
     * Get the number of messages in memory.
     */
    int size();

    /**
     * Estimate the total number of tokens in the conversation.
     * This is a rough estimation based on character count.
     *
     * @return estimated token count
     */
    long estimateTokens();

    /**
     * Compress the conversation history using the configured LLM.
     * Replaces the conversation with a compressed summary while preserving system messages.
     *
     * @return true if compression was successful, false otherwise
     */
    boolean compress();

    /**
     * Set the LLM client to use for compression.
     *
     * @param llmClient the LLM client for compression
     */
    void setCompressionLLMClient(LLMClient llmClient);

    /**
     * Set a custom compression prompt template.
     * The template should contain {conversation} placeholder.
     *
     * @param prompt the compression prompt template
     */
    void setCompressionPrompt(String prompt);
}
