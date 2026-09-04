package com.agent.core.llm;

/**
 * 大模型参数配置。
 * 请使用 builder 创建实例。
 *
 * 示例：
 * <pre>
 * {@code
 * LLMParams params = LLMParams.builder()
 *     .temperature(0.8)
 *     .topP(0.95)
 *     .maxCompletionTokens(4096)
 *     .build();
 * }
 * </pre>
 */
public record LLMParams(
        Double temperature,
        Double topP,
        Integer maxCompletionTokens,
        Double frequencyPenalty,
        Double presencePenalty,
        Long seed,
        String stop
) {

    public static final double DEFAULT_TEMPERATURE = 0.7;
    public static final int DEFAULT_MAX_COMPLETION_TOKENS = 4096;

    /**
     * 默认参数：temperature=0.7，maxCompletionTokens=4096。
     */
    public static final LLMParams DEFAULT = builder().build();

    /**
     * 实际应用到本次大模型调用的 temperature：配置值，若未配置则取默认值。
     */
    public double effectiveTemperature() {
        return temperature != null ? temperature : DEFAULT_TEMPERATURE;
    }

    /**
     * 实际应用到本次大模型调用的最大补全 Token 数：配置值，若未配置则取默认值。
     */
    public int effectiveMaxCompletionTokens() {
        return maxCompletionTokens != null ? maxCompletionTokens : DEFAULT_MAX_COMPLETION_TOKENS;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建一个仅设置 temperature 的参数对象。
     */
    public static LLMParams withTemperature(double temperature) {
        return builder().temperature(temperature).build();
    }

    public static class Builder {
        private Double temperature;
        private Double topP;
        private Integer maxCompletionTokens;
        private Double frequencyPenalty;
        private Double presencePenalty;
        private Long seed;
        private String stop;

        private Builder() {}

        /**
         * 控制随机性：范围 0.0 到 2.0，值越小输出越确定。
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * 核采样（nucleus sampling）参数：范围 0.0 到 1.0。
         */
        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * 补全中允许生成的最大 Token 数。
         */
        public Builder maxCompletionTokens(int maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        /**
         * 根据 Token 在当前文本中出现的频率施加惩罚，降低重复。
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * 根据 Token 是否已出现在当前文本中施加惩罚，鼓励引入新话题。
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * 用于生成确定性输出的随机种子。
         */
        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * 模型应当停止生成的终止序列。
         */
        public Builder stop(String stop) {
            this.stop = stop;
            return this;
        }

        public LLMParams build() {
            return new LLMParams(temperature, topP, maxCompletionTokens,
                    frequencyPenalty, presencePenalty, seed, stop);
        }
    }
}
