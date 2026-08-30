package com.agent.core.model;

import java.util.List;

/**
 * Response from the LLM containing the assistant message and full response metadata.
 *
 * @param message      the assistant's response message
 * @param id           provider-assigned response id (null when unavailable)
 * @param model        the model that actually produced the response (null when unavailable)
 * @param finishReason why generation stopped, e.g. "stop", "tool_calls", "length" (null when unavailable)
 * @param usage        token usage statistics, {@link TokenUsage#NONE} when the provider returned none
 */
public record LLMResponse(
        Message message,
        String id,
        String model,
        String finishReason,
        TokenUsage usage
) {

    public LLMResponse {
        usage = usage != null ? usage : TokenUsage.NONE;
    }

    /**
     * Create a response without provider metadata (usage defaults to {@link TokenUsage#NONE}).
     */
    public LLMResponse(Message message) {
        this(message, null, null, null, TokenUsage.NONE);
    }

    public int promptTokens() {
        return (int) usage.promptTokens();
    }

    public int completionTokens() {
        return (int) usage.completionTokens();
    }

    public int totalTokens() {
        return (int) usage.totalTokens();
    }

    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }

    public List<ToolCall> toolCalls() {
        return message != null && message.toolCalls() != null ? message.toolCalls() : List.of();
    }

    public String content() {
        return message != null ? message.content() : null;
    }
}
