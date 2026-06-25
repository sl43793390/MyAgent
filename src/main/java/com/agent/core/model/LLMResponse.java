package com.agent.core.model;

import java.util.List;

/**
 * Response from the LLM containing the assistant message and usage statistics.
 *
 * @param message the assistant's response message
 * @param promptTokens     number of tokens in the prompt
 * @param completionTokens number of tokens in the completion
 */
public record LLMResponse(
        Message message,
        int promptTokens,
        int completionTokens
) {

    public int totalTokens() {
        return promptTokens + completionTokens;
    }

    public boolean hasToolCalls() {
        return message != null && message.hasToolCalls();
    }

    public List<ToolCall> toolCalls() {
        return message != null ? message.toolCalls() : List.of();
    }

    public String content() {
        return message != null ? message.content() : null;
    }
}
