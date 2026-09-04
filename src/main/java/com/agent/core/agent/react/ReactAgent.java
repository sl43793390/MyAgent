package com.agent.core.agent.react;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.BaseAgent;
import com.agent.core.agent.RunBudget;
import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.Memory;
import com.agent.core.model.*;
import com.agent.core.observer.AgentObserver;
import com.agent.core.session.SessionManager;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;

/**
 * ReAct 智能体：思考（Thought）&rarr; 动作（Action）&rarr; 观察结果（Observation）循环。
 *
 * <ol>
 *   <li>模型对当前情况进行分析推理。</li>
 *   <li>它调用某个工具来收集信息或执行动作。</li>
 *   <li>工具结果被追加，循环重复进行。</li>
 *   <li>当模型在不发起工具调用的情况下给出答案时，循环结束。</li>
 * </ol>
 *
 * <p>线程安全：无状态的 {@code run(input)} 会创建自身的上下文，可安全地并发调用。
 * 共享同一 {@code sessionId} 的并发调用由会话锁串行化，因此一个轮次绝不会
 * 与同一对话的另一个轮次交错执行。
 *
 * <p>当模型给出最终答案、已执行 {@code maxIterations} 个步骤、或 {@link RunBudget}
 * 耗尽时，运行停止。提供方（provider）失败会以 {@link com.agent.core.llm.LLMException}
 * 的形式抛出且<b>不会</b>被吞掉 —— 调用方由此可以决定是否重试，
 * 因为异常会说明重试是否有帮助。
 */
public class ReactAgent extends BaseAgent {

    private static final String DEFAULT_SYSTEM_PROMPT = """
            You are a helpful AI assistant that uses a reasoning loop to solve problems.

            You have access to tools that you can use to gather information or perform actions.

            For each step:
            1. Think about what you need to do next
            2. If you need information or action, use an appropriate tool
            3. Once you have enough information, provide a clear and complete final answer

            When you are ready to give your final answer, respond with text only (no tool calls).
            Be thorough and helpful in your final answer.
            """;

    /** 单次运行的 LLM 往返次数上限。 */
    public static final RunBudget DEFAULT_BUDGET = RunBudget.of(50, 200, 0);

    private final int maxIterations;
    private final RunBudget budget;

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxIterations) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, maxIterations, LLMParams.DEFAULT,
                DEFAULT_BUDGET);
    }

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, LLMParams.DEFAULT, DEFAULT_BUDGET);
    }

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations, LLMParams llmParams) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, llmParams, DEFAULT_BUDGET);
    }

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations, LLMParams llmParams, RunBudget budget) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, llmParams, budget, null);
    }

    /**
     * @param sessionManager 外部配置的会话管理器，为 null 则使用默认值
     */
    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations, LLMParams llmParams, RunBudget budget,
                      SessionManager sessionManager) {
        super(llmClient, toolRegistry, systemPrompt, llmParams, sessionManager);
        this.maxIterations = maxIterations > 0 ? maxIterations : 10;
        this.budget = budget != null ? budget : DEFAULT_BUDGET;
    }

    /**
     * 无状态运行：全新的上下文，不使用会话。
     */
    @Override
    public AgentResult run(String userInput) {
        log.info("ReactAgent started with input: {}", userInput);
        Memory context = createContext();
        context.add(Message.user(userInput));
        return executeWithContext(context);
    }

    /**
     * 会话运行：上下文在多次调用之间保留，因此整个轮次针对该会话 id 串行化执行。
     */
    @Override
    public AgentResult run(String userInput, String sessionId) {
        log.info("ReactAgent started with input: {} (session: {})", userInput, sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return run(userInput);
        }
        return sessionManager.withSessionLock(sessionId, () -> {
            Memory context = getSessionContext(sessionId);
            context.add(Message.user(userInput));
            return executeWithContext(context);
        });
    }

    private AgentResult executeWithContext(Memory context) {
        RunBudget.Tracker tracker = budget.newRun();
        TokenUsage totalUsage = TokenUsage.NONE;
        String model = null;
        String finishReason = null;
        int llmCallCount = 0;
        int step = 0;

        while (step < maxIterations) {
            if (tracker.exhausted()) {
                log.warn("ReactAgent stopped: {}", tracker.reason());
                return finish(context, "Stopped: " + tracker.reason(), step,
                        model, finishReason, llmCallCount, totalUsage);
            }

            step++;
            log.debug("--- ReactAgent step {} ---", step);

            AgentObserver currentObserver = observer;
            if (currentObserver != null) {
                currentObserver.onStepStart(step, "react");
            }

            try {
                LLMResponse response = callLLM(context);
                llmCallCount++;
                tracker.recordLlmCall(response.usage());
                totalUsage = totalUsage.plus(response.usage());
                if (response.model() != null) {
                    model = response.model();
                }
                finishReason = response.finishReason();

                Message assistantMessage = response.message();
                if (assistantMessage == null) {
                    log.error("LLM returned no message at step {}", step);
                    if (currentObserver != null) {
                        currentObserver.onStepEnd(step, "react");
                    }
                    return finish(context, "The model returned an empty response.", step,
                            model, finishReason, llmCallCount, totalUsage);
                }
                context.add(assistantMessage);

                if (response.hasToolCalls()) {
                    for (ToolCall toolCall : response.toolCalls()) {
                        tracker.recordToolCall();
                        log.debug("Calling tool {} with args {}", toolCall.name(), toolCall.arguments());

                        // 工具注册表将未知工具和执行失败作为 ToolResult 上报，
                        // 而非抛出异常，这样模型能看到错误并在下一次调用中纠正，
                        // 而不是让运行直接终止。
                        ToolResult result = toolRegistry.execute(toolCall.name(), toolCall.arguments());
                        context.add(Message.tool(result.text(), toolCall.id(), toolCall.name()));
                    }
                    if (currentObserver != null) {
                        currentObserver.onStepEnd(step, "react");
                    }
                    continue;
                }

                if (currentObserver != null) {
                    currentObserver.onStepEnd(step, "react");
                }
                log.info("ReactAgent completed in {} step(s)", step);
                return finish(context, response.content(), step,
                        model, finishReason, llmCallCount, totalUsage);

            } catch (RuntimeException e) {
                // 提供方错误带有类型化原因（鉴权 / 限流 / 无效请求）；
                // 重新抛出以便调用方据此作出反应。
                if (currentObserver != null) {
                    currentObserver.onStepEnd(step, "react");
                }
                throw e;
            }
        }

        log.warn("ReactAgent reached max iterations ({})", maxIterations);
        return finish(context,
                "Reached maximum iterations (" + maxIterations + ") without a final answer.",
                step, model, finishReason, llmCallCount, totalUsage);
    }

    private AgentResult finish(Memory context, String output, int steps, String model,
                               String finishReason, int llmCallCount, TokenUsage usage) {
        return new AgentResult(output, steps, context.getMessages(),
                model, finishReason, llmCallCount, usage);
    }

    /**
     * 每次运行的最大循环迭代次数。
     */
    public int maxIterations() {
        return maxIterations;
    }

    /**
     * 应用到每次运行上的成本上限。
     */
    public RunBudget budget() {
        return budget;
    }
}
