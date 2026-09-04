package com.agent.core.agent;

import com.agent.core.model.TokenUsage;

/**
 * 单次智能体运行的支出上限。
 *
 * <p>智能体循环是系统中唯一一处"这次要花多少钱？"没有静态答案的地方：由模型决定要走多少步。
 * 若没有预算，一个陷入混乱的模型可能在一次用户请求中就烧穿整个上下文窗口——或一份 API 配额——
 * 而唯一的发现方式就是账单。
 *
 * <p>{@code RunBudget} 是一个不可变的 <i>specification</i>；每次智能体运行调用一次 {@link #newRun()}
 * 即可获得强制执行该规格的可变 {@link Tracker}：
 *
 * <pre>{@code
 * RunBudget budget = RunBudget.of(20, 50, 200_000);   // LLM calls, tool calls, tokens
 * RunBudget.Tracker tracker = budget.newRun();
 * }</pre>
 *
 * <p>限制值为 {@code 0}（默认值）表示不限制。
 */
public final class RunBudget {

    /** 永不停下运行的预算。 */
    public static final RunBudget UNLIMITED = new RunBudget(0, 0, 0);

    private final long maxLlmCalls;
    private final long maxToolCalls;
    private final long maxTotalTokens;

    private RunBudget(long maxLlmCalls, long maxToolCalls, long maxTotalTokens) {
        this.maxLlmCalls = Math.max(0, maxLlmCalls);
        this.maxToolCalls = Math.max(0, maxToolCalls);
        this.maxTotalTokens = Math.max(0, maxTotalTokens);
    }

    /** 一个没有任何限制的预算。 */
    public static RunBudget unlimited() {
        return UNLIMITED;
    }

    /** 限制 LLM 调用次数的预算。 */
    public static RunBudget llmCalls(long maxLlmCalls) {
        return new RunBudget(maxLlmCalls, 0, 0);
    }

    /** 限制 Token 总量的预算。 */
    public static RunBudget tokens(long maxTotalTokens) {
        return new RunBudget(0, 0, maxTotalTokens);
    }

    /**
     * 完整预算。
     *
     * @param maxLlmCalls    最大大模型调用轮次（0 = 不限制）
     * @param maxToolCalls   最大工具调用次数（0 = 不限制）
     * @param maxTotalTokens 所有大模型调用累计的最大 Token 数（0 = 不限制）
     */
    public static RunBudget of(long maxLlmCalls, long maxToolCalls, long maxTotalTokens) {
        return new RunBudget(maxLlmCalls, maxToolCalls, maxTotalTokens);
    }

    public long maxLlmCalls() {
        return maxLlmCalls;
    }

    public long maxToolCalls() {
        return maxToolCalls;
    }

    public long maxTotalTokens() {
        return maxTotalTokens;
    }

    /**
     * 开始针对本预算追踪一次新的运行。
     */
    public Tracker newRun() {
        return new Tracker(this);
    }

    @Override
    public String toString() {
        return "RunBudget[llmCalls=" + describe(maxLlmCalls)
                + ", toolCalls=" + describe(maxToolCalls)
                + ", totalTokens=" + describe(maxTotalTokens) + "]";
    }

    private static String describe(long limit) {
        return limit <= 0 ? "unlimited" : String.valueOf(limit);
    }

    /**
     * 每次运行的消耗计数器。设计上并非线程安全：一个追踪器归属于一次运行。
     */
    public static final class Tracker {

        private final RunBudget budget;
        private long llmCalls;
        private long toolCalls;
        private long totalTokens;

        private Tracker(RunBudget budget) {
            this.budget = budget;
        }

        /**
         * 记录一次大模型调用轮次及其 Token 用量。
         *
         * @return 一旦运行超过限制即返回 false
         */
        public boolean recordLlmCall(TokenUsage usage) {
            llmCalls++;
            if (usage != null) {
                totalTokens += usage.totalTokens();
            }
            return !exhausted();
        }

        /**
         * 记录一次工具调用。
         *
         * @return 一旦运行超过限制即返回 false
         */
        public boolean recordToolCall() {
            toolCalls++;
            return !exhausted();
        }

        /**
         * 是否已触及任一限制。
         */
        public boolean exhausted() {
            return (budget.maxLlmCalls > 0 && llmCalls >= budget.maxLlmCalls)
                    || (budget.maxToolCalls > 0 && toolCalls >= budget.maxToolCalls)
                    || (budget.maxTotalTokens > 0 && totalTokens >= budget.maxTotalTokens);
        }

        /**
         * 运行结束的原因，若尚未结束则为 null。
         */
        public String reason() {
            if (budget.maxLlmCalls > 0 && llmCalls >= budget.maxLlmCalls) {
                return "LLM call budget exhausted (" + llmCalls + "/" + budget.maxLlmCalls + ")";
            }
            if (budget.maxToolCalls > 0 && toolCalls >= budget.maxToolCalls) {
                return "Tool call budget exhausted (" + toolCalls + "/" + budget.maxToolCalls + ")";
            }
            if (budget.maxTotalTokens > 0 && totalTokens >= budget.maxTotalTokens) {
                return "Token budget exhausted (" + totalTokens + "/" + budget.maxTotalTokens + ")";
            }
            return null;
        }

        public long llmCalls() {
            return llmCalls;
        }

        public long toolCalls() {
            return toolCalls;
        }

        public long totalTokens() {
            return totalTokens;
        }

        @Override
        public String toString() {
            return "RunBudget.Tracker[llmCalls=" + llmCalls + ", toolCalls=" + toolCalls
                    + ", totalTokens=" + totalTokens + ", exhausted=" + exhausted() + "]";
        }
    }
}
