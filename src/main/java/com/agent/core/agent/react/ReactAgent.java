package com.agent.core.agent.react;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.BaseAgent;
import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.Memory;
import com.agent.core.model.*;
import com.agent.core.tool.ToolRegistry;

/**
 * React Agent implementation.
 *
 * The React (Reasoning + Acting) pattern follows a Thought -> Action -> Observation loop:
 * 1. The LLM reasons about the current situation (Thought)
 * 2. It decides to take an action by calling a tool (Action)
 * 3. The tool result is observed and fed back (Observation)
 * 4. The loop continues until the LLM decides to provide a final answer
 *
 * Thread-safe: Each run() invocation creates its own execution context.
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

    private final int maxIterations;

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, int maxIterations) {
        this(llmClient, toolRegistry, DEFAULT_SYSTEM_PROMPT, maxIterations, LLMParams.DEFAULT);
    }

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations) {
        this(llmClient, toolRegistry, systemPrompt, maxIterations, LLMParams.DEFAULT);
    }

    public ReactAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                      int maxIterations, LLMParams llmParams) {
        super(llmClient, toolRegistry, systemPrompt, llmParams);
        this.maxIterations = maxIterations;
    }

    @Override
    public AgentResult run(String userInput) {
        log.info("ReactAgent started with input: {}", userInput);

        // Create local execution context for thread safety
        Memory context = createContext();
        context.add(Message.user(userInput));

        return executeWithContext(context);
    }

    @Override
    public AgentResult run(String userInput, String sessionId) {
        log.info("ReactAgent started with input: {} (session: {})", userInput, sessionId);

        // Get or create session context for multi-turn conversation
        Memory context = getSessionContext(sessionId);
        context.add(Message.user(userInput));

        return executeWithContext(context);
    }

    private AgentResult executeWithContext(Memory context) {
        int totalTokens = 0;
        int step = 0;

        while (step < maxIterations) {
            step++;
            log.info("--- ReactAgent Step {} ---", step);

            // Notify observer
            if (observer != null) {
                observer.onStepStart(step, "react");
            }

            LLMResponse response = callLLM(context);
            totalTokens += response.totalTokens();

            Message assistantMessage = response.message();
            context.add(assistantMessage);

            // Check if the LLM wants to use tools
            if (response.hasToolCalls()) {
//                log.info("LLM requested {} tool call(s)", response.toolCalls().size());

                for (ToolCall toolCall : response.toolCalls()) {
//                    log.info("Calling tool: {} with args: {}", toolCall.name(), toolCall.arguments());

                    // Execute tool
                    String toolResult;
                    if (toolRegistry.has(toolCall.name())) {
                        toolResult = toolRegistry.execute(toolCall.name(), toolCall.arguments());
                    } else {
                        toolResult = "Error: Tool '" + toolCall.name() + "' not found";
                    }

//                    log.info("Tool result: {}", truncate(toolResult, 200));

                    // Add tool result to context
                    context.add(Message.tool(toolResult, toolCall.id(), toolCall.name()));
                }
            } else {
                // Notify observer
                if (observer != null) {
                    observer.onStepEnd(step, "react");
                }

                // No tool calls - this is the final answer
//                log.info("ReactAgent completed in {} steps", step);
                String output = response.content();
                return new AgentResult(output, step, totalTokens);
            }

            // Notify observer
            if (observer != null) {
                observer.onStepEnd(step, "react");
            }
        }

        // Max iterations reached
        log.warn("ReactAgent reached max iterations ({})", maxIterations);
        return new AgentResult("Reached maximum iterations without a final answer.", step, totalTokens);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
