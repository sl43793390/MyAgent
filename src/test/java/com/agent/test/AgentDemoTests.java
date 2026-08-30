package com.agent.test;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.plan.PlanAndExecuteAgent;
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.MemoryFactory;
import com.agent.core.memory.MySQLMemory;
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
    private static final String BASE_URL = System.getenv("BASE_URL");

    /**
     * Demo 1: Basic React Agent with built-in tools.
     *
     * Shows how to:
     * - Create an OpenAI client
     * - Register built-in tools
     * - Run a React Agent 需要带有记忆的agent，必须传入用户ID，支持多轮对话，【单轮对话则不需要传入】
     */
    public static void testReactAgentWithBuiltinTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // Create LLM client
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // Register tools
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new DateTimeTool());

        LoggingObserver observer = new LoggingObserver(true);

        // 附加到组件
//        llmClient.setObserver(observer);
//        registry.setObserver(observer);
        // Create and run agent
        String sessionId = "user-123";
        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        agent.setObserver(observer);
//        设置自定义对话存储方式
//        agent.setMemoryFactory(id -> {
//            return new MySQLMemory("jdbc:mysql://192.168.80.151:3306/my_agent","root","test",
//                    "chat_memory",id,100000);
//        });
        AgentResult result = agent.run("Calculate (15 + 27) * 3 and tell me the current time", sessionId);

        System.out.println("=== React Agent Result ===");
        System.out.println("Output: " + result.output());
//        System.out.println("Steps: " + result.totalSteps());
//        System.out.println("Tokens: " + result.totalTokens());
         AgentResult result2 = agent.run("我们刚才聊了什么问题，总结一下", sessionId);
        System.out.println("=== React Agent Result2 ===");
        System.out.println("Output: " + result2.output());
//        System.out.println("Steps: " + result2.totalSteps());
//        System.out.println("Tokens: " + result2.totalTokens());
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
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // Create tool registry and processor
        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        LoggingObserver observer = new LoggingObserver(true);

        // 附加到组件
        llmClient.setObserver(observer);
        registry.setObserver(observer);
        // Register custom tool
        processor.register(new WeatherTools());

        // Create and run agent
        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        AgentResult result = agent.run("北京天气怎么样？我是否应该带雨伞？");

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
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // Register tools
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());

        // Create agent with replanning enabled
        PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
                llmClient,
                registry,
                10,
                true
        );
        LoggingObserver observer = new LoggingObserver(true);
        registry.setObserver(observer);
        agent.setObserver(observer);
        llmClient.setObserver(observer);
        // Run complex task
        AgentResult result = agent.run(
                "计算1000美元以年利率5%计息3年的复利总额。\n" +
                        "然后计算该金额折合人民币的具体数额（假设1美元=7.2元人民币）。"
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

        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        // Register multiple custom tools
        processor.register(new WeatherTools());
        processor.register(new CurrencyTools());
        registry.register(new CalculatorTool());

        ReactAgent agent = new ReactAgent(llmClient, registry, 15);
        //加日志打印
        LoggingObserver observer = new LoggingObserver(true);
        registry.setObserver(observer);
        agent.setObserver(observer);
        llmClient.setObserver(observer);

        AgentResult result = agent.run(
                "我计划去马尔代夫旅行。请查看当地的天气情况。\n" +
                        "将10000 CNY 兑换成美元，并计算我的预算是否充足"
        );

        System.out.println("=== Multiple Tools Result ===");
        System.out.println("Output: " + result.output());
    }

    /**
     * Main method to run all demos.
     */
    public static void main(String[] args) {
//        System.out.println("Starting Agent Demo Tests...\n");

        System.out.println("Demo 1: React Agent with Built-in Tools");
        testReactAgentWithBuiltinTools();
        System.out.println();

//         System.out.println("Demo 2: React Agent with Custom Tools");
//         testReactAgentWithCustomTools();
//         System.out.println();

//         System.out.println("Demo 3: Plan-and-Execute Agent");
//         testPlanAndExecuteAgent();
//         System.out.println();

//         System.out.println("Demo 4: Multiple Custom Tools");
//         testMultipleCustomTools();
//         System.out.println();

        System.out.println("All demos completed!");
    }
}




