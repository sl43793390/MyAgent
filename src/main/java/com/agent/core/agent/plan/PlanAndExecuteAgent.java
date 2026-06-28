package com.agent.core.agent.plan;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.BaseAgent;
import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.Memory;
import com.agent.core.model.*;
import com.agent.core.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.List;

/**
 * Plan-and-Execute Agent implementation.
 *
 * This agent follows a two-phase approach:
 * 1. Planning Phase: The LLM creates a step-by-step plan to solve the problem
 * 2. Execution Phase: Each step is executed sequentially, with the LLM using tools as needed
 * 3. Replanning Phase (optional): After executing steps, the agent can revise the plan if needed
 *
 * Thread-safe: Each run() invocation creates its own execution context.
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

    private final int maxSteps;
    private final boolean enableReplanning;

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps) {
        this(llmClient, toolRegistry, maxSteps, true, LLMParams.DEFAULT);
    }

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps,
                               boolean enableReplanning) {
        this(llmClient, toolRegistry, maxSteps, enableReplanning, LLMParams.DEFAULT);
    }

    public PlanAndExecuteAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxSteps,
                               boolean enableReplanning, LLMParams llmParams) {
        super(llmClient, toolRegistry, null, llmParams);
        this.maxSteps = maxSteps;
        this.enableReplanning = enableReplanning;
    }

    @Override
    public AgentResult run(String userInput) {
        log.info("PlanAndExecuteAgent started with task: {}", userInput);
        return executeTask(userInput);
    }

    @Override
    public AgentResult run(String userInput, String sessionId) {
        log.info("PlanAndExecuteAgent started with task: {} (session: {})", userInput, sessionId);
        // Note: Plan-and-Execute creates fresh contexts for each phase (planning/execution),
        // so the session is logged but not used for context reuse.
        return executeTask(userInput);
    }

    private AgentResult executeTask(String userInput) {
        int totalTokens = 0;
        int totalSteps = 0;

        // Phase 1: Planning
        log.info("=== Planning Phase ===");
        if (observer != null) {
            observer.onStepStart(0, "plan");
        }

        PlanResult planResult = createPlan(userInput);
        totalTokens += planResult.tokensUsed();
        List<String> steps = planResult.steps();

        log.info("Plan created with {} steps:", steps.size());
        for (int i = 0; i < steps.size(); i++) {
            log.info("  {}. {}", i + 1, steps.get(i));
        }

        if (observer != null) {
            observer.onStepEnd(0, "plan");
        }

        // Phase 2: Execution
        List<String> completedResults = new ArrayList<>();

        for (int i = 0; i < steps.size() && totalSteps < maxSteps; i++) {
            totalSteps++;
            String currentStep = steps.get(i);
            log.info("=== Executing Step {}/{}: {} ===", i + 1, steps.size(), currentStep);

            if (observer != null) {
                observer.onStepStart(totalSteps, "execute");
            }

            ExecutionResult result = executeStep(userInput, currentStep, completedResults);
            totalTokens += result.tokensUsed();
            completedResults.add(result.summary());

            log.info("Step {} completed: {}", i + 1, truncate(result.summary(), 200));

            if (observer != null) {
                observer.onStepEnd(totalSteps, "execute");
            }

            // Phase 3: Replanning (optional)
            if (enableReplanning && i < steps.size() - 1) {
                log.info("=== Replanning Phase ===");

                if (observer != null) {
                    observer.onStepStart(totalSteps, "replan");
                }

                ReplanResult replanResult = replan(userInput, steps, completedResults, i + 1);
                totalTokens += replanResult.tokensUsed();

                if (replanResult.isComplete()) {
                    log.info("Replanner determined task is complete");
                    if (observer != null) {
                        observer.onStepEnd(totalSteps, "replan");
                    }
                    break;
                }

                if (!replanResult.steps().isEmpty()) {
                    // Replace remaining steps with new plan
                    steps = replanResult.steps();
                    i = -1; // Reset to start of new plan (will be incremented to 0)
                    log.info("Plan revised, {} remaining steps", steps.size());
                }

                if (observer != null) {
                    observer.onStepEnd(totalSteps, "replan");
                }
            }
        }

        // Generate final answer
        String finalAnswer = generateFinalAnswer(userInput, completedResults);
        log.info("PlanAndExecuteAgent completed in {} steps", totalSteps);

        return new AgentResult(finalAnswer, totalSteps, totalTokens);
    }

    private PlanResult createPlan(String task) {
        Memory planMemory = new InMemoryStore();
        planMemory.add(Message.system(PLANNER_SYSTEM_PROMPT));
        planMemory.add(Message.user("Create a plan for the following task:\n\n" + task));

        LLMResponse response = llmClient.chat(planMemory.getMessages(), List.of(), llmParams);
        String planText = response.content();
        List<String> steps = parsePlan(planText);

        return new PlanResult(steps, response.totalTokens());
    }

    private ExecutionResult executeStep(String task, String step, List<String> completedSteps) {
        String prompt = EXECUTOR_SYSTEM_PROMPT
                .replace("{task}", task)
                .replace("{step}", step)
                .replace("{completedSteps}", completedSteps.isEmpty()
                        ? "None yet"
                        : String.join("\n", completedSteps));

        Memory execMemory = new InMemoryStore();
        execMemory.add(Message.system(prompt));
        execMemory.add(Message.user("Execute the step: " + step));

        int tokensUsed = 0;
        int iterations = 0;
        int maxIterations = 5;

        while (iterations < maxIterations) {
            iterations++;
            LLMResponse response = llmClient.chat(execMemory.getMessages(), toolRegistry.getDefinitions(), llmParams);
            tokensUsed += response.totalTokens();

            Message assistantMessage = response.message();
            execMemory.add(assistantMessage);

            if (response.hasToolCalls()) {
                for (ToolCall toolCall : response.toolCalls()) {
                    log.info("Executor calling tool: {}", toolCall.name());
                    String toolResult = toolRegistry.has(toolCall.name())
                            ? toolRegistry.execute(toolCall.name(), toolCall.arguments())
                            : "Error: Tool '" + toolCall.name() + "' not found";

                    execMemory.add(Message.tool(toolResult, toolCall.id(), toolCall.name()));
                }
            } else {
                return new ExecutionResult(response.content(), tokensUsed);
            }
        }

        return new ExecutionResult("Step execution reached maximum iterations", tokensUsed);
    }

    private ReplanResult replan(String task, List<String> originalPlan,
                                List<String> completedResults, int completedIndex) {
        StringBuilder context = new StringBuilder();
        context.append("Original task: ").append(task).append("\n\n");
        context.append("Original plan:\n");
        for (int i = 0; i < originalPlan.size(); i++) {
            context.append(String.format("%d. %s\n", i + 1, originalPlan.get(i)));
        }
        context.append("\nCompleted steps results:\n");
        for (int i = 0; i < completedResults.size(); i++) {
            context.append(String.format("Step %d: %s\n", i + 1, completedResults.get(i)));
        }
        context.append("\nNext step to execute: ").append(
                completedIndex < originalPlan.size() ? originalPlan.get(completedIndex) : "N/A"
        );

        Memory replanMemory = new InMemoryStore();
        replanMemory.add(Message.system(REPLANNER_SYSTEM_PROMPT));
        replanMemory.add(Message.user(context.toString()));

        LLMResponse response = llmClient.chat(replanMemory.getMessages(), List.of(), llmParams);
        String replanText = response.content();

        if (replanText != null && replanText.trim().equalsIgnoreCase("COMPLETE")) {
            return new ReplanResult(List.of(), true, response.totalTokens());
        }

        List<String> newSteps = parsePlan(replanText);
        return new ReplanResult(newSteps, false, response.totalTokens());
    }

    private String generateFinalAnswer(String task, List<String> completedResults) {
        StringBuilder sb = new StringBuilder();
        sb.append("Task: ").append(task).append("\n\nResults:\n");
        for (int i = 0; i < completedResults.size(); i++) {
            sb.append(String.format("Step %d: %s\n", i + 1, completedResults.get(i)));
        }
        return sb.toString();
    }

    private List<String> parsePlan(String planText) {
        List<String> steps = new ArrayList<>();
        if (planText == null || planText.isBlank()) {
            return steps;
        }

        for (String line : planText.split("\n")) {
            line = line.trim();
            // Match numbered list items: "1. step", "1) step", etc.
            if (line.matches("^\\d+[.)]\\s+.*")) {
                String step = line.replaceFirst("^\\d+[.)]\\s+", "").trim();
                if (!step.isEmpty()) {
                    steps.add(step);
                }
            }
        }

        // If no numbered steps found, treat each non-empty line as a step
        if (steps.isEmpty()) {
            for (String line : planText.split("\n")) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    steps.add(line);
                }
            }
        }

        return steps;
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }

    // Internal records
    private record PlanResult(List<String> steps, int tokensUsed) {}
    private record ExecutionResult(String summary, int tokensUsed) {}
    private record ReplanResult(List<String> steps, boolean isComplete, int tokensUsed) {}
}
