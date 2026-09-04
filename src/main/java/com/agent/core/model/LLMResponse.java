package com.agent.core.model;

import java.util.List;

/**
 * 来自 LLM 的响应，包含助手消息及完整的响应元数据。
 *
 * @param message     助手的响应消息
 * @param id          服务商分配的响应 ID（不可用时为 null）
 * @param model       实际生成该响应的模型（不可用时为 null）
 * @param finishReason 生成停止的原因，例如 "stop"、"tool_calls"、"length"（不可用时为 null）
 * @param usage       令牌用量统计；当服务商未返回时为 {@link TokenUsage#NONE}
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
     * 创建不含服务商元数据的响应（用量默认为 {@link TokenUsage#NONE}）。
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
