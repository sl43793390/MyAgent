package com.agent.example;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.plan.PlanAndExecuteAgent;
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.LLMClient;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.tool.builtin.CalculatorTool;
import com.agent.core.tool.builtin.DateTimeTool;
import com.agent.core.tool.builtin.WebFetchTool;
import com.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Example application demonstrating the Java Agent Framework.
 *
 * This example shows how to:
 * 1. Configure an LLM client
 * 2. Register tools
 * 3. Create and run React Agent
 * 4. Create and run Plan-and-Execute Agent
 */
public class ExampleApp {

    private static final Logger log = LoggerFactory.getLogger(ExampleApp.class);

    public static void main(String[] args) {
        // Get API key from environment variable
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENAI_API_KEY environment variable is not set");
            log.info("Please set your API key: export OPENAI_API_KEY=your-api-key");
            return;
        }

        // Initialize LLM client
        LLMClient llmClient = OpenAILLMClient.openAI(apiKey, "gpt-4o-mini");

        // Register tools
        ToolRegistry toolRegistry = new ToolRegistry();
        toolRegistry.register(new CalculatorTool());
        toolRegistry.register(new DateTimeTool());
        toolRegistry.register(new WebFetchTool());

        log.info("=== Java Agent Framework Example ===\n");

        // Example 1: React Agent
        runReactAgentExample(llmClient, toolRegistry);

        // Example 2: Plan-and-Execute Agent
        runPlanAndExecuteAgentExample(llmClient, toolRegistry);
    }

    private static void runReactAgentExample(LLMClient llmClient, ToolRegistry toolRegistry) {
        log.info("=== Example 1: React Agent ===");
        log.info("Task: Calculate the result of (15 + 27) * 3 and tell me the current time\n");

        ReactAgent agent = new ReactAgent(
                llmClient,
                toolRegistry,
                10
        );

        AgentResult result = agent.run("Calculate the result of (15 + 27) * 3 and tell me the current time");

        log.info("\n=== React Agent Result ===");
        log.info("Output:\n{}", result.output());
        log.info("Total steps: {}", result.totalSteps());
        log.info("Total tokens: {}", result.totalTokens());
        log.info("\n");
    }

    private static void runPlanAndExecuteAgentExample(LLMClient llmClient, ToolRegistry toolRegistry) {
        log.info("=== Example 2: Plan-and-Execute Agent ===");
        log.info("Task: Research the benefits of exercise and create a summary\n");

        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
                llmClient,
                toolRegistry,
                10,
                true
        );

        AgentResult result = agent.run("Research the benefits of exercise and create a summary with at least 3 key points");

        log.info("\n=== Plan-and-Execute Agent Result ===");
        log.info("Output:\n{}", result.output());
        log.info("Total steps: {}", result.totalSteps());
        log.info("Total tokens: {}", result.totalTokens());
    }
}
