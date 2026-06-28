package com.agent.test;

import com.agent.core.agent.AgentResult;
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.MySQLMemory;
import com.agent.core.memory.RedisMemory;
import com.agent.core.observer.LoggingObserver;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CalculatorTool;
import com.agent.core.tool.builtin.DateTimeTool;

/**
 * 演示如何在不同记忆实现（InMemory / Redis / MySQL）之间切换。
 *
 * <p>核心 API：{@code agent.setMemoryFactory(factory)}
 * 一行代码切换，agent 的无状态模式和会话模式都会生效。
 *
 * <p>运行前请设置环境变量 OPENAI_API_KEY。
 * Redis / MySQL 演示需要本地服务，若不可用会自动跳过。
 */
public class MemorySwitchDemo {

    private static final String API_KEY = System.getenv("OPENAI_API_KEY");
    private static final String BASE_URL = "https://www.example.cn/v1";
    private static final String MODEL = "deepseek-v4-flash";

    public static void main(String[] args) {
        if (API_KEY == null || API_KEY.isBlank()) {
            System.err.println("请先设置环境变量 OPENAI_API_KEY");
            return;
        }
        demoInMemory();
        demoRedis();
        demoMySQL();
    }

    // ========== Demo 1: InMemoryStore（默认，无需额外配置） ==========

    public static void demoInMemory() {
        System.out.println("====== Demo 1: InMemoryStore（默认） ======");

        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, MODEL);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new DateTimeTool());

        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        agent.setObserver(new LoggingObserver(true));

        // 默认就是 InMemoryStore，无需额外设置；
        // 也可以显式指定：
        agent.setMemoryFactory(sessionId -> new InMemoryStore());

        String sessionId = "user-inmemory";
        AgentResult r1 = agent.run("帮我计算 123 * 456", sessionId);
        printResult("第1轮", r1);

        AgentResult r2 = agent.run("刚才的计算结果是多少？", sessionId);
        printResult("第2轮", r2);

        System.out.println();
    }

    // ========== Demo 2: RedisMemory ==========

    public static void demoRedis() {
        System.out.println("====== Demo 2: RedisMemory ======");

        RedisMemory probe = null;
        try {
            // 探测 Redis 是否可用
            probe = new RedisMemory("localhost", 6379, "probe", 0);
            probe.size();
        } catch (Exception e) {
            System.out.println("Redis 不可用，跳过本演示: " + e.getMessage());
            if (probe != null) {
                probe.close();
            }
            System.out.println();
            return;
        }
        probe.close();

        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, MODEL);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new DateTimeTool());

        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        agent.setObserver(new LoggingObserver(true));

        // ★ 关键：一行切换到 Redis
        // 每个 sessionId 对应一个独立的 Redis key，TTL 1 小时
        agent.setMemoryFactory(sessionId ->
                new RedisMemory("localhost", 6379, "agent:session:" + sessionId, 3600));

        String sessionId = "user-redis";
        agent.clearSession(sessionId); // 确保干净开始

        AgentResult r1 = agent.run("帮我计算 789 + 321", sessionId);
        printResult("第1轮", r1);

        AgentResult r2 = agent.run("刚才我们聊了什么？", sessionId);
        printResult("第2轮", r2);

        // 关闭 Redis 连接池（生产中应在应用关闭时调用）
        // 注意：setMemoryFactory 后每次新 session 会新建 RedisMemory，
        // 这里仅为演示，实际应统一管理连接池
        System.out.println();
    }

    // ========== Demo 3: MySQLMemory ==========

    public static void demoMySQL() {
        System.out.println("====== Demo 3: MySQLMemory ======");

        var llmClient = OpenAILLMClient.openAI(API_KEY, BASE_URL, MODEL);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CalculatorTool());
        registry.register(new DateTimeTool());

        ReactAgent agent = new ReactAgent(llmClient, registry, 10);
        agent.setObserver(new LoggingObserver(true));

        String jdbcUrl = "jdbc:mysql://localhost:3306/agent?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";

        // 探测 MySQL 是否可用
        try (MySQLMemory probe = new MySQLMemory(jdbcUrl, "root", "root", "agent_messages", "probe")) {
            probe.size();
        } catch (Exception e) {
            System.out.println("MySQL 不可用，跳过本演示: " + e.getMessage());
            System.out.println();
            return;
        }

        // ★ 关键：一行切换到 MySQL
        // 每个 sessionId 对应数据库中一组消息行
        agent.setMemoryFactory(sessionId ->
                new MySQLMemory(jdbcUrl, "root", "root", "agent_messages", sessionId));

        String sessionId = "user-mysql";
        agent.clearSession(sessionId);

        AgentResult r1 = agent.run("帮我计算 999 - 111", sessionId);
        printResult("第1轮", r1);

        AgentResult r2 = agent.run("刚才的计算结果是多少？", sessionId);
        printResult("第2轮", r2);

        System.out.println();
    }

    private static void printResult(String label, AgentResult result) {
        System.out.println("--- " + label + " ---");
        System.out.println("输出: " + result.output());
        System.out.println("步数: " + result.totalSteps());
        System.out.println("Token: " + result.totalTokens());
    }
}
