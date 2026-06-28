package com.agent.core.llm;

/**
 * LLM model parameters configuration.
 * Use the builder to create instances.
 *
 * Example:
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

    /**
     * Default parameters: temperature=0.7, maxCompletionTokens=4096.
     */
    public static final LLMParams DEFAULT = builder().build();

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Create params with only temperature set.
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
         * Controls randomness: 0.0 to 2.0. Lower = more deterministic.
         */
        public Builder temperature(double temperature) {
            this.temperature = temperature;
            return this;
        }

        /**
         * Nucleus sampling parameter: 0.0 to 1.0.
         */
        public Builder topP(double topP) {
            this.topP = topP;
            return this;
        }

        /**
         * Maximum number of tokens in the completion.
         */
        public Builder maxCompletionTokens(int maxCompletionTokens) {
            this.maxCompletionTokens = maxCompletionTokens;
            return this;
        }

        /**
         * Penalizes tokens based on their frequency in the text so far.
         */
        public Builder frequencyPenalty(double frequencyPenalty) {
            this.frequencyPenalty = frequencyPenalty;
            return this;
        }

        /**
         * Penalizes tokens based on whether they appear in the text so far.
         */
        public Builder presencePenalty(double presencePenalty) {
            this.presencePenalty = presencePenalty;
            return this;
        }

        /**
         * Seed for deterministic output.
         */
        public Builder seed(long seed) {
            this.seed = seed;
            return this;
        }

        /**
         * Stop sequence where the model should stop generating.
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
