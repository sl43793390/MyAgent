package com.agent.core.agent.plan;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.BaseAgent;
import com.agent.core.agent.RunBudget;
import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.Memory;
import com.agent.core.model.*;
import com.agent.core.observer.AgentObserver;
import com.agent.core.session.SessionManager;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.ToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Plan-and-Execute 智能体。
 *
 * <ol>
 *   <li><b>规划（Plan）</b> —— 模型将任务分解为有序的步骤。</li>
 *   <li><b>执行（Execute）</b> —— 每个步骤作为一个小型 ReAct 循环运行，并可调用工具。</li>
 *   <li><b>重新规划（Replan）</b> —— 一个步骤完成后，模型可以修订剩余计划。</li>
 *   <li><b>综合（Synthesize）</b> —— 模型将各步骤结果整合为单一答案。</li>
 * </ol>
 *
 * <h3>改动说明</h3>
 * <p>旧实现对全部四个阶段都采用一个针对步骤列表的单一 {@code for} 循环来驱动，
 * 而重新规划通过将 {@code i = -1} 赋给<i>外层</i>循环变量来回退循环 —— 这是一种
 * 控制流技巧，仅因恰好随后执行了自增才得以工作，这也使得终止条件难以推理。
 * 现在的各阶段是一个基于待执行步骤队列的显式状态机：
 *
 * <pre>
 *   PLAN ──▶ EXECUTE ──▶ REPLAN ──▶ EXECUTE ──▶ ... ──▶ SYNTHESIZE ──▶ DONE
 *              │            │                              ▲
 *              └────────────┴──────────────────────────────┘
 * </pre>
 *
 * <p>重新规划用替换待执行队列的方式来代替回退索引，并由 {@code maxReplans} 加以限制，
 * 这样一个不断重写计划的模型就无法无限循环下去。
 *
 * <p>另一处修复是契约层面的：{@link #run(String, String)} 过去会接收 {@code sessionId}、
 * 记录它，然后却忽略它 —— 子类会悄无声息地破坏父类的契约。现在它真正使用了会话：
 * 任务与最终答案都会被记录到会话历史中，因此后续轮次能获得它预期的上下文。
 */
public class PlanAndExecuteAgent extends BaseAgent {

    private static final String PLANNER_SYSTEM_PROMPT = """
            You are a planning AI assistant. Given a task, break it down into clear, actionable steps.

            Rules:
            - Each step should be a single, clear action
            - Steps should be ordered logically
            - Be specific about what each step should accomplish
            - Consider what tools might be available for each step
            - If the task is simple, create a simple plan with few steps

            Output your plan as a numbered list, one step per line.
            Format:
            1. First step description
            2. Second step description
            ...
            """;

    private static final String EXECUTOR_SYSTEM_PROMPT = """
            You are an execution AI assistant. You are given a specific step to execute as part of a larger plan.

            You have access to tools that you can use to complete the step.

            Context:
            - Original task: {task}
            - Current step: {step}
            - Steps completed so far: {completedSteps}

            Execute the current step. If you need to use tools, do so. When you have completed the step,
            provide a clear summary of what was accomplished.
            """;

    private static final String REPLANNER_SYSTEM_PROMPT = """
            You are a planning AI assistant. Given the original plan and results so far,
            decide if the plan needs to be revised.

            If the plan is still valid and there are remaining steps, output the remaining steps.
            If the plan needs revision, output a new plan.
            If the task is complete, output "COMPLETE".

            Output format:
            - If continuing: numbered list of remaining/new steps
            - If complete: just the word "COMPLETE"
            """;

    private static final String SYNTHESIZER_SYSTEM_PROMPT = """
            You are a reporting AI assistant. Given a task and the results of the steps that were
            executed to complete it, write a clear, self-contained final answer for the user.

            Rules:
            - Answer the task directly; do not describe the plan or the process
            - Mention anything that could not be completed
            - Do not invent results that are not in the step results

            Task:
            {task}

            Step results:
            {results}

            Final answer:
            """;

    /** 单次运行的 LLM 往返次数上限（规划、执行与重新规划合计）。 */
    public static final RunBudget DEFAULT_BUDGET = RunBudget.of(60, 400, 0);

    private final int maxSteps;
    private final int maxIterationsPerStep;
    private final int maxReplans;
    private final boolean enableReplanning;
    private final RunBudget budget;

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps) {
        this(llmClient, toolRegistry, maxSteps, true, LLMParams.DEFAULT);
    }

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps,
                               boolean enableReplanning) {
        this(llmClient, toolRegistry, maxSteps, enableReplanning, LLMParams.DEFAULT);
    }

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps,
                               boolean enableReplanning, LLMParams llmParams) {
        this(llmClient, toolRegistry, maxSteps, enableReplanning, 5, 3, llmParams, DEFAULT_BUDGET, null);
    }

    /**
     * 对 plan-and-execute 循环的完全控制。
     *
     * @param maxSteps            每次运行执行的计划步骤最大数量
     * @param enableReplanning    是否允许在步骤之间修订计划
     * @param maxIterationsPerStep 执行单个步骤时允许的模型往返次数
     * @param maxReplans          每次运行的最大重新规划轮数
     * @param llmParams           每次 LLM 调用的参数
     * @param budget              整个运行的成本上限
     * @param sessionManager      外部配置的会话管理器，为 null 则使用默认值
     */
    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps,
                               boolean enableReplanning, int maxIterationsPerStep, int maxReplans,
                               LLMParams llmParams, RunBudget budget, SessionManager sessionManager) {
        super(llmClient, toolRegistry, null, llmParams, sessionManager);
        this.maxSteps = maxSteps > 0 ? maxSteps : 10;
        this.enableReplanning = enableReplanning;
        this.maxIterationsPerStep = maxIterationsPerStep > 0 ? maxIterationsPerStep : 5;
        this.maxReplans = Math.max(0, maxReplans);
        this.budget = budget != null ? budget : DEFAULT_BUDGET;
    }

    /**
     * 无状态运行：在不触及任何会话的情况下完成规划、执行与综合。
     */
    @Override
    public AgentResult run(String userInput) {
        log.info("PlanAndExecuteAgent started with task: {}", userInput);
        return executeTask(userInput, null);
    }

    /**
     * 会话运行：任务与最终答案会被追加到会话历史中，因此后续轮次可以看到已完成的内容。
     * 整个轮次针对该会话 id 串行化执行。
     */
    @Override
    public AgentResult run(String userInput, String sessionId) {
        log.info("PlanAndExecuteAgent started with task: {} (session: {})", userInput, sessionId);
        if (sessionId == null || sessionId.isBlank()) {
            return run(userInput);
        }
        return sessionManager.withSessionLock(sessionId, () -> {
            Memory session = getSessionContext(sessionId);
            session.add(Message.user(userInput));
            return executeTask(userInput, session);
        });
    }

    // ---------------------------------------------------------------- 状态机

    private enum Phase { PLAN, EXECUTE, REPLAN, SYNTHESIZE, DONE }

    private AgentResult executeTask(String task, Memory session) {
        RunStats stats = new RunStats();
        RunBudget.Tracker tracker = budget.newRun();

        Deque<String> pendingSteps = new ArrayDeque<>();
        List<String> completedResults = new ArrayList<>();

        int executedSteps = 0;
        int replans = 0;
        Phase phase = Phase.PLAN;
        String finalAnswer = null;

        while (phase != Phase.DONE) {
            if (tracker.exhausted()) {
                log.warn("PlanAndExecuteAgent stopped: {}", tracker.reason());
                finalAnswer = "Stopped: " + tracker.reason();
                break;
            }

            switch (phase) {
                case PLAN -> {
                    notifyStep(0, "plan", true);
                    PhaseResult plan = createPlan(task, tracker);
                    stats.merge(plan);
                    pendingSteps.addAll(plan.steps());
                    notifyStep(0, "plan", false);

                    log.info("Plan created with {} step(s)", pendingSteps.size());
                    if (pendingSteps.isEmpty()) {
                        log.warn("Planner produced no steps; falling back to executing the task directly");
                        pendingSteps.add(task);
                    }
                    phase = Phase.EXECUTE;
                }

                case EXECUTE -> {
                    if (pendingSteps.isEmpty() || executedSteps >= maxSteps) {
                        phase = Phase.SYNTHESIZE;
                        break;
                    }
                    String step = pendingSteps.poll();
                    executedSteps++;
                    log.info("=== Executing step {}/{}: {} ===", executedSteps, maxSteps, step);

                    notifyStep(executedSteps, "execute", true);
                    PhaseResult execution = executeStep(task, step, completedResults, tracker);
                    stats.merge(execution);
                    completedResults.add(execution.summary());
                    notifyStep(executedSteps, "execute", false);

                    log.info("Step {} completed: {}", executedSteps, truncate(execution.summary(), 200));

                    phase = (enableReplanning && replans < maxReplans && !pendingSteps.isEmpty())
                            ? Phase.REPLAN
                            : (pendingSteps.isEmpty() ? Phase.SYNTHESIZE : Phase.EXECUTE);
                }

                case REPLAN -> {
                    replans++;
                    log.info("=== Replanning (round {}) ===", replans);

                    notifyStep(executedSteps, "replan", true);
                    PhaseResult replan = replan(task, completedResults, List.copyOf(pendingSteps), tracker);
                    stats.merge(replan);
                    notifyStep(executedSteps, "replan", false);

                    if (replan.complete()) {
                        log.info("Replanner declared the task complete");
                        pendingSteps.clear();
                        phase = Phase.SYNTHESIZE;
                        break;
                    }
                    if (!replan.steps().isEmpty()) {
                        // 用替换待执行队列的方式代替回退循环索引。
                        pendingSteps.clear();
                        pendingSteps.addAll(replan.steps());
                        log.info("Plan revised: {} pending step(s)", pendingSteps.size());
                    }
                    phase = pendingSteps.isEmpty() ? Phase.SYNTHESIZE : Phase.EXECUTE;
                }

                case SYNTHESIZE -> {
                    notifyStep(-1, "synthesize", true);
                    PhaseResult synthesis = synthesize(task, completedResults, tracker);
                    stats.merge(synthesis);
                    finalAnswer = synthesis.summary();
                    notifyStep(-1, "synthesize", false);
                    phase = Phase.DONE;
                }

                case DONE -> {
                    // 不可达：循环条件在 DONE 时退出。
                }
            }
        }

        if (finalAnswer == null || finalAnswer.isBlank()) {
            finalAnswer = fallbackAnswer(task, completedResults);
        }

        if (session != null) {
            session.add(Message.assistant(finalAnswer));
        }

        log.info("PlanAndExecuteAgent finished after {} executed step(s)", executedSteps);
        return new AgentResult(finalAnswer, executedSteps, stats.transcript,
                stats.model, stats.finishReason, stats.llmCalls, stats.usage);
    }

    private void notifyStep(int stepNumber, String phase, boolean start) {
        AgentObserver currentObserver = observer;
        if (currentObserver == null) {
            return;
        }
        if (start) {
            currentObserver.onStepStart(stepNumber, phase);
        } else {
            currentObserver.onStepEnd(stepNumber, phase);
        }
    }

    // ---------------------------------------------------------------- 阶段

    private PhaseResult createPlan(String task, RunBudget.Tracker tracker) {
        Memory memory = newPhaseMemory(PLANNER_SYSTEM_PROMPT,
                "Create a plan for the following task:\n\n" + task);

        LLMResponse response = llmClient.chat(memory.getMessages(), List.of(), llmParams);
        tracker.recordLlmCall(response.usage());
        List<String> steps = parsePlan(response.content());

        return new PhaseResult(steps, false, null, response.model(), response.finishReason(),
                1, response.usage(), transcript(memory, response));
    }

    private PhaseResult executeStep(String task, String step, List<String> completedSteps,
                                    RunBudget.Tracker tracker) {
        String prompt = EXECUTOR_SYSTEM_PROMPT
                .replace("{task}", task)
                .replace("{step}", step)
                .replace("{completedSteps}", completedSteps.isEmpty()
                        ? "None yet"
                        : String.join("\n", completedSteps));

        Memory memory = newPhaseMemory(prompt, "Execute the step: " + step);

        TokenUsage usage = TokenUsage.NONE;
        String model = null;
        String finishReason = null;
        int iterations = 0;

        while (iterations < maxIterationsPerStep) {
            if (tracker.exhausted()) {
                return new PhaseResult(null, false,
                        "Step execution stopped: " + tracker.reason(),
                        model, finishReason, iterations, usage, memory.getMessages());
            }

            iterations++;
            LLMResponse response = llmClient.chat(
                    memory.getMessages(), toolRegistry.getDefinitions(), llmParams);
            tracker.recordLlmCall(response.usage());
            usage = usage.plus(response.usage());
            if (response.model() != null) {
                model = response.model();
            }
            finishReason = response.finishReason();

            Message assistantMessage = response.message();
            if (assistantMessage == null) {
                return new PhaseResult(null, false, "The model returned an empty response.",
                        model, finishReason, iterations, usage, memory.getMessages());
            }
            memory.add(assistantMessage);

            if (!response.hasToolCalls()) {
                return new PhaseResult(null, false,
                        response.content() != null ? response.content() : "",
                        model, finishReason, iterations, usage, memory.getMessages());
            }

            for (ToolCall toolCall : response.toolCalls()) {
                tracker.recordToolCall();
                log.debug("Executor calling tool: {}", toolCall.name());
                ToolResult result = toolRegistry.execute(toolCall.name(), toolCall.arguments());
                memory.add(Message.tool(result.text(), toolCall.id(), toolCall.name()));
            }
        }

        return new PhaseResult(null, false,
                "Step execution reached the maximum of " + maxIterationsPerStep + " iterations.",
                model, finishReason, iterations, usage, memory.getMessages());
    }

    private PhaseResult replan(String task, List<String> completedResults,
                               List<String> pendingSteps, RunBudget.Tracker tracker) {
        StringBuilder context = new StringBuilder();
        context.append("Original task: ").append(task).append("\n\n");
        context.append("Steps completed so far:\n");
        for (int i = 0; i < completedResults.size(); i++) {
            context.append(String.format("Step %d: %s%n", i + 1, completedResults.get(i)));
        }
        context.append("\nRemaining planned steps:\n");
        if (pendingSteps.isEmpty()) {
            context.append("None\n");
        } else {
            for (int i = 0; i < pendingSteps.size(); i++) {
                context.append(String.format("%d. %s%n", i + 1, pendingSteps.get(i)));
            }
        }

        Memory memory = newPhaseMemory(REPLANNER_SYSTEM_PROMPT, context.toString());
        LLMResponse response = llmClient.chat(memory.getMessages(), List.of(), llmParams);
        tracker.recordLlmCall(response.usage());

        String text = response.content();
        if (text != null && text.trim().equalsIgnoreCase("COMPLETE")) {
            return new PhaseResult(List.of(), true, null, response.model(), response.finishReason(),
                    1, response.usage(), transcript(memory, response));
        }

        return new PhaseResult(parsePlan(text), false, null, response.model(),
                response.finishReason(), 1, response.usage(), transcript(memory, response));
    }

    private PhaseResult synthesize(String task, List<String> completedResults,
                                   RunBudget.Tracker tracker) {
        if (completedResults.isEmpty()) {
            return new PhaseResult(null, false,
                    "No plan steps were executed, so the task could not be completed.",
                    null, null, 0, TokenUsage.NONE, List.of());
        }

        StringBuilder results = new StringBuilder();
        for (int i = 0; i < completedResults.size(); i++) {
            results.append(String.format("Step %d: %s%n", i + 1, completedResults.get(i)));
        }

        String prompt = SYNTHESIZER_SYSTEM_PROMPT
                .replace("{task}", task)
                .replace("{results}", results.toString());

        Memory memory = newPhaseMemory(prompt, "Write the final answer for the task.");
        LLMResponse response = llmClient.chat(memory.getMessages(), List.of(), llmParams);
        tracker.recordLlmCall(response.usage());

        String answer = response.content();
        if (answer == null || answer.isBlank()) {
            return new PhaseResult(null, false, fallbackAnswer(task, completedResults),
                    response.model(), response.finishReason(), 1, response.usage(),
                    transcript(memory, response));
        }

        return new PhaseResult(null, false, answer.trim(), response.model(),
                response.finishReason(), 1, response.usage(), transcript(memory, response));
    }

    private static Memory newPhaseMemory(String systemPrompt, String userPrompt) {
        Memory memory = new InMemoryStore();
        memory.add(Message.system(systemPrompt));
        memory.add(Message.user(userPrompt));
        return memory;
    }

    private static List<Message> transcript(Memory memory, LLMResponse response) {
        List<Message> messages = new ArrayList<>(memory.getMessages());
        if (response.message() != null) {
            messages.add(response.message());
        }
        return messages;
    }

    private String fallbackAnswer(String task, List<String> completedResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n\nResults:\n");
        for (int i = 0; i < completedResults.size(); i++) {
            sb.append(String.format("Step %d: %s%n", i + 1, completedResults.get(i)));
        }
        return sb.toString();
    }

    /**
     * 从自由的模型输出中提取计划步骤：优先取编号行，否则取所有非空的、非 Markdown 标题的行。
     */
    private List<String> parsePlan(String planText) {
        List<String> steps = new ArrayList<>();
        if (planText == null || planText.isBlank()) {
            return steps;
        }

        for (String line : planText.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                String step = trimmed.replaceFirst("^\\d+[.)]\\s+", "").trim();
                if (!step.isEmpty()) {
                    steps.add(step);
                }
            }
        }

        if (steps.isEmpty()) {
            for (String line : planText.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    steps.add(trimmed);
                }
            }
        }

        // 一个与整个任务完全相同的单一步骤并无额外信息；不过仍保留它 ——
        // 调用方在计划为空时本来就会回退到该情况。
        return steps;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "null";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // ---------------------------------------------------------------- 访问器

    public int maxSteps() {
        return maxSteps;
    }

    public int maxIterationsPerStep() {
        return maxIterationsPerStep;
    }

    public int maxReplans() {
        return maxReplans;
    }

    public boolean isReplanningEnabled() {
        return enableReplanning;
    }

    public RunBudget budget() {
        return budget;
    }

    // ---------------------------------------------------------------- 辅助方法

    /** 单个阶段（规划 / 执行 / 重新规划 / 综合）的结果。 */
    private record PhaseResult(
            List<String> steps,
            boolean complete,
            String summary,
            String model,
            String finishReason,
            int llmCalls,
            TokenUsage usage,
            List<Message> messages
    ) {}

    /** 合并单次运行中各阶段结果的可变累加器。 */
    private static final class RunStats {
        private TokenUsage usage = TokenUsage.NONE;
        private String model;
        private String finishReason;
        private int llmCalls;
        private final List<Message> transcript = new ArrayList<>();

        private void merge(PhaseResult phase) {
            usage = usage.plus(phase.usage());
            llmCalls += phase.llmCalls();
            if (phase.model() != null) {
                model = phase.model();
            }
            finishReason = phase.finishReason();
            transcript.addAll(phase.messages());
        }
    }
}
