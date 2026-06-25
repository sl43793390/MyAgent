package com.agent.test;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.plan.PlanAndExecuteAgent;
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.observer.LoggingObserver;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.annotation.AnnotationToolProcessor;
import com.agent.core.tool.builtin.CalculatorTool;
import com.agent.core.tool.builtin.DateTimeTool;
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

/**
 * Demo tests showing how to use the Java Agent Framework.
 *
 * These are example methods demonstrating various agent patterns.
 * To run these demos, set the OPENAI_API_KEY environment variable.
 */
public class AgentDemoTests {

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");

    /**
     * Demo 1: Basic React Agent with built-in tools.
     *
     * Shows how to:
     * - Create an OpenAI client
     * - Register built-in tools
     * - Run a React Agent
     */
    public static void testReactAgentWithBuiltinTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // Create LLM client
        var llmClient = OpenAILLMClient.openAI(API_KEY, "https://www.dmxapi.cn/v1", "deepseek-v4-flash");

        // Register tools
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new DateTimeTool());
        LoggingObserver observer = new LoggingObserver(true);
// 附加到组件
        llmClient.setObserver(observer);
        registry.setObserver(observer);
        // Create and run agent
        ReactAgent agent = new ReactAgent(llmClient, registry, new InMemoryStore(), null, 10);
        agent.setObserver(observer);
        AgentResult result = agent.run("Calculate (15 + 27) * 3 and tell me the current time");

        System.out.println("=== React Agent Result ===");
        System.out.println("Output: " + result.output());
        System.out.println("Steps: " + result.totalSteps());
        System.out.println("Tokens: " + result.totalTokens());
    }

    /**
     * Demo 2: React Agent with custom annotated tools.
     *
     * Shows how to:
     * - Create custom tools using @Tool annotation
     * - Register them with AnnotationToolProcessor
     * - Use them in a React Agent
     */
    public static void testReactAgentWithCustomTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // Create LLM client
        var llmClient = OpenAILLMClient.openAI(API_KEY, "https://www.dmxapi.cn/v1", "deepseek-v4-flash");

        // Create tool registry and processor
        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        // Register custom tool
        processor.register(new WeatherTools());

        // Create and run agent
        ReactAgent agent = new ReactAgent(llmClient, registry, new InMemoryStore(), null, 10);
        AgentResult result = agent.run("What's the weather in Beijing? Should I bring an umbrella?");

        System.out.println("=== Custom Tools Result ===");
        System.out.println("Output: " + result.output());
    }

    /**
     * Demo 3: Plan-and-Execute Agent.
     *
     * Shows how to:
     * - Create a Plan-and-Execute Agent
     * - Use it for complex multi-step tasks
     */
    public static void testPlanAndExecuteAgent() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // Create LLM client
        var llmClient = OpenAILLMClient.openAI(API_KEY, "https://www.dmxapi.cn/v1", "deepseek-v4-flash");

        // Register tools
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());

        // Create agent with replanning enabled
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
                llmClient,
                registry,
                new InMemoryStore(),
                10,
                true
        );

        // Run complex task
        AgentResult result = agent.run(
                "Calculate the compound interest on $1000 at 5% annual rate for 3 years, " +
                        "then calculate how much that would be in Chinese Yuan (assume 1 USD = 7.2 CNY)"
        );

        System.out.println("=== Plan-and-Execute Result ===");
        System.out.println("Output: " + result.output());
        System.out.println("Steps: " + result.totalSteps());
    }

    /**
     * Demo 4: Multiple custom tools working together.
     *
     * Shows how to:
     * - Register multiple custom tool classes
     * - Combine built-in and custom tools
     */
    public static void testMultipleCustomTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        var llmClient = OpenAILLMClient.openAI(API_KEY, "gpt-4o-mini");

        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        // Register multiple custom tools
        processor.register(new WeatherTools());
        registry.register(new CalculatorTool());

        ReactAgent agent = new ReactAgent(llmClient, registry, new InMemoryStore(), null, 15);
        AgentResult result = agent.run(
                "I'm planning a trip to Tokyo. Check the weather there, " +
                        "convert 10000 JPY to USD, and calculate if I have enough budget"
        );

        System.out.println("=== Multiple Tools Result ===");
        System.out.println("Output: " + result.output());
    }

    /**
     * Main method to run all demos.
     */
    public static void main(String[] args) {
        System.out.println("Starting Agent Demo Tests...\n");

        System.out.println("Demo 1: React Agent with Built-in Tools");
        testReactAgentWithBuiltinTools();
        System.out.println();

//        System.out.println("Demo 2: React Agent with Custom Tools");
//        testReactAgentWithCustomTools();
//        System.out.println();
//
//        System.out.println("Demo 3: Plan-and-Execute Agent");
//        testPlanAndExecuteAgent();
//        System.out.println();
//
//        System.out.println("Demo 4: Multiple Custom Tools");
//        testMultipleCustomTools();
//        System.out.println();

        System.out.println("All demos completed!");
    }
}
