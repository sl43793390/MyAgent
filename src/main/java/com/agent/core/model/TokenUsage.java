package com.agent.core.model;

/**
 * LLM 返回的令牌用量元数据。
 *
 * @param promptTokens       输入（prompt）消耗的令牌数
 * @param completionTokens   模型生成的令牌数
 * @param totalTokens         promptTokens 与 completionTokens 之和
 * @param cachedPromptTokens 来自提示词缓存的令牌数（未知时为 0）
 * @param reasoningTokens    用于内部推理的令牌数，例如推理模型（未知时为 0）
 */
public record TokenUsage(
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long cachedPromptTokens,
        long reasoningTokens
) {

    /** 共享实例，用于不携带任何用量信息的响应。 */
    public static final TokenUsage NONE = new TokenUsage(0, 0, 0, 0, 0);

    /**
     * 仅用顶层计数创建用量对象；明细计数均为 0。
     */
    public static TokenUsage of(long promptTokens, long completionTokens, long totalTokens) {
        return new TokenUsage(promptTokens, completionTokens, totalTokens, 0, 0);
    }

    /**
     * 返回本用量与 {@code other} 的合计，用于跨多次 LLM 调用聚合用量。
     */
    public TokenUsage plus(TokenUsage other) {
        return new TokenUsage(
                promptTokens + other.promptTokens,
                completionTokens + other.completionTokens,
                totalTokens + other.totalTokens,
                cachedPromptTokens + other.cachedPromptTokens,
                reasoningTokens + other.reasoningTokens
        );
    }
}
