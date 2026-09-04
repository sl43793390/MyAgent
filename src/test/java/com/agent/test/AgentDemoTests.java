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
import com.agent.core.tool.builtin.*;
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

/**
 * 演示测试：展示如何使用该 Java 智能体框架。
 *
 * 以下方法均为示例，演示各种智能体模式。
 * 运行这些演示前，请设置环境变量 OPENAI_API_KEY。
 */
public class AgentDemoTests {

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String BASE_URL = System.getenv("BASE_URL");

    /**
     * 演示 1：使用内置工具的基础 React 智能体。
     *
     * 演示内容包括：
     * - 创建 OpenAI 客户端
     * - 注册内置工具
     * - 运行需要记忆的 React 智能体：必须传入用户ID，支持多轮对话（单轮对话则不需要传入）
     */
    public static void testReactAgentWithBuiltinTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // 创建 LLM 客户端
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // 注册工具
        ToolRegistry registry = new ToolRegistry();
        // CommandExecutionTool 具有危险性（可执行任意 shell 命令）：注册表要求显式开启（opt-in）。
        // 仅当智能体输入可信且进程已处于沙箱环境时，才应启用此项。
        registry.setAllowDangerousTools(true);
        registry.register(new CommandExecutionTool());
        registry.register(new DateTimeTool());
        registry.register(new FileEditTool());
        registry.register(new FileListTool());
        registry.register(new FileWriteTool());
        registry.register(new FileReadTool());

        LoggingObserver observer = new LoggingObserver(true);

        // 附加到组件
        llmClient.setObserver(observer);
        registry.setObserver(observer);
        // 创建并运行智能体
        String sessionId = "user-123";
        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        agent.setObserver(observer);
//        设置自定义对话存储方式
//        agent.setMemoryFactory(id -> {
//            return new MySQLMemory("jdbc:mysql://192.168.80.151:3306/my_agent","root","test",
//                    "chat_memory",id,100000);
//        });
//        AgentResult result = agent.run("Calculate (15 + 27) * 3 and tell me the current time", sessionId);
        AgentResult result = agent.run("今天几号了", sessionId);

        System.out.println("=== React Agent Result ===");
        System.out.println("Output: " + result.output());
//        System.out.println("Steps: " + result.totalSteps());
//        System.out.println("Tokens: " + result.totalTokens());

//         AgentResult result2 = agent.run("我们刚才聊了什么问题，总结一下", sessionId);
//        System.out.println("=== React Agent Result2 ===");
//        System.out.println("Output: " + result2.output());
//        System.out.println("Steps: " + result2.totalSteps());
//        System.out.println("Tokens: " + result2.totalTokens());
    }

    /**
     * 演示 2：使用自定义注解工具的 React 智能体。
     *
     * 演示内容包括：
     * - 使用 @Tool 注解创建自定义工具
     * - 通过 AnnotationToolProcessor 注册工具
     * - 在 React 智能体中使用这些工具
     */
    public static void testReactAgentWithCustomTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // 创建 LLM 客户端
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // 创建工具注册表与处理器
        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        LoggingObserver observer = new LoggingObserver(true);

        // 附加到组件
        llmClient.setObserver(observer);
        registry.setObserver(observer);
        // 注册自定义工具
//        processor.register(new WeatherTools());

        // 创建并运行智能体
        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        AgentResult result = agent.run("北京天气怎么样？我是否应该带雨伞？");

        System.out.println("=== Custom Tools Result ===");
        System.out.println("Output: " + result.output());
    }

    /**
     * 演示 3：Plan-and-Execute（规划-执行）型智能体。
     *
     * 演示内容包括：
     * - 创建 Plan-and-Execute 智能体
     * - 将其用于复杂的多步骤任务
     */
    public static void testPlanAndExecuteAgent() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        // 创建 LLM 客户端
        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        // 注册工具
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());

        // 创建启用重新规划的智能体
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
        // 运行复杂任务
        AgentResult result = agent.run(
                "计算1000美元以年利率5%计息3年的复利总额。\n" +
                        "然后计算该金额折合人民币的具体数额（假设1美元=7.2元人民币）。"
        );

        System.out.println("=== Plan-and-Execute Result ===");
        System.out.println("Output: " + result.output());
        System.out.println("Steps: " + result.totalSteps());
    }

    /**
     * 演示 4：多个自定义工具协同工作。
     *
     * 演示内容包括：
     * - 注册多个自定义工具类
     * - 组合使用内置工具与自定义工具
     */
    public static void testMultipleCustomTools() {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("OPENAI_API_KEY not set");
            return;
        }

        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, "glm-5.3-flash");

        ToolRegistry registry = new ToolRegistry();
        AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);

        // 注册多个自定义工具
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
     * 主方法：运行全部演示。
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
