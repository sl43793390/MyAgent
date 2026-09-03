# Java Agent 框架（my-agent）

一个基于 Java 21 的 Agent 框架，实现了 React Agent 和 Plan-and-Execute Agent 模式，用于构建 AI 驱动的应用程序。它是**一个库**（`com.agent:my-agent:1.0.0`），不内置可独立运行的 main 类；可运行 demo 位于 `src/test`。

## 特性

- **React Agent**：实现 Thought → Action → Observation 推理循环，支持有界步数、会话串行与成本预算。
- **Plan-and-Execute Agent**：显式状态机，包含 Plan → Execute → Replan → Synthesize 阶段，可对会话上下文真正生效。
- **工具系统**：可扩展的 `ToolRegistry`，支持超时与风险等级 gating；内置 10 个工具。
- **注解式工具**：用 `@Tool` / `@ToolParam` 注解创建工具（Spring AI 风格）。
- **内存管理**：`Memory` 抽象 + `AbstractMemory` 基类，支持内存 / Redis / MySQL 后端，可一行切换，含阈值自动压缩。
- **安全边界**：`PathSandbox`（文件沙箱）、`UrlGuard`（防 SSRF）、`ToolPolicy`（能力准入）；危险工具默认拒绝注册。
- **可观察性**：`AgentObserver` 接口，监控 LLM 调用、工具执行与 Agent 步骤。
- **会话管理**：`SessionManager` 带 TTL、容量上限、LRU 淘汰与 per-session 锁。
- **OpenAI SDK**：基于官方 OpenAI Java SDK（4.52.0），兼容任意 OpenAI 兼容端点。
- **Java 21**：使用 record、文本块、switch 表达式等现代特性。

## 架构

```
src/main/java/com/agent/
└── core/
    ├── agent/
    │   ├── BaseAgent.java               # 抽象基类：会话/预算/压缩编排
    │   ├── AgentResult.java             # Agent 执行结果
    │   ├── RunBudget.java               # 单次运行的 LLM/工具/token 预算
    │   ├── react/ReactAgent.java        # ReAct Agent
    │   └── plan/PlanAndExecuteAgent.java
    ├── llm/
    │   ├── LLMClient.java               # LLM 客户端接口
    │   ├── LLMParams.java               # 采样/生成参数
    │   ├── OpenAILLMClient.java         # OpenAI SDK 实现 + LLMException 层次
    │   └── LLMException.java
    ├── memory/
    │   ├── Memory.java / AbstractMemory.java / MemoryFactory.java
    │   ├── InMemoryStore.java / RedisMemory.java / MySQLMemory.java
    │   ├── MemoryCompressor.java / TokenEstimator.java / JsonSupport.java
    ├── model/
    │   ├── Message.java / Role.java / ToolCall.java
    │   ├── LLMResponse.java / TokenUsage.java
    ├── observer/
    │   ├── AgentObserver.java / LoggingObserver.java
    ├── security/
    │   ├── PathSandbox.java / UrlGuard.java / ToolPolicy.java
    ├── session/SessionManager.java
    └── tool/
        ├── Tool.java                     # execute 返回 ToolResult；含 timeout()/riskLevel()
        ├── ToolDefinition.java / ToolResult.java / RiskLevel.java / ToolRegistry.java
        ├── annotation/
        │   ├── Tool.java (@Tool) / ToolParam.java
        │   ├── AnnotationToolProcessor.java / AnnotatedMethodTool.java
        └── builtin/
            ├── CalculatorTool / DateTimeTool / WebFetchTool
            ├── FileReadTool / FileWriteTool / FileEditTool / FileListTool / FileSearchTool
            └── CommandExecutionTool / Args.java
src/test/java/com/agent/test/
    ├── AgentDemoTests.java  WeatherTools.java  CurrencyTools.java  MemorySwitchDemo.java
pom.xml
```

> 早期 README 中标注的 `example/ExampleApp.java` 与 `com.agent.App` 在实际代码中**不存在**，本模块以库形态交付。

## 前置要求

- Java 21 或更高
- Maven 3.8+
- OpenAI API 密钥（或任一 OpenAI 兼容端点，如 DeepSeek / GLM / vLLM / Ollama / One-API）

## 构建与运行示例

```bash
cd d:\ideaSpace\MyAgent
mvn clean package
```

本框架是可复用库，不产生可 `java -jar` 运行的主类；示例运行方式（需先设置 `OPENAI_API_KEY`）：

```bash
# 方式一：直接运行测试类（推荐，含多轮记忆演示）
mvn test -Dtest=AgentDemoTests  # 该类是 demo 而非断言测试，请直接调用其 main
java -cp target/classes:$(依赖classpath) com.agent.test.MemorySwitchDemo
```

## 配置

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key-here
# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key-here"
# （可选）自定义端点
# export OPENAI_BASE_URL=https://your-gateway/v1
```

## 使用方法

### 使用 React Agent

```java
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CalculatorTool;

LLMClient llmClient = OpenAILLMClient.openAI(apiKey, "gpt-4o-mini"); // 或 openAI(apiKey, baseUrl, model)

ToolRegistry registry = new ToolRegistry();
registry.register(new CalculatorTool());

ReactAgent agent = new ReactAgent(llmClient, registry, 10); // 10 = max iterations

// 无状态单轮
AgentResult result = agent.run("计算 (15 + 27) * 3");
System.out.println(result.output());

// 带记忆的多轮会话（同一 sessionId 复用上下文）
String sessionId = "user-123";
agent.run("帮我计算 15*27", sessionId);
AgentResult r2 = agent.run("刚才的结果是多少？", sessionId);
```

> 注解：带记忆需传入 `sessionId`；同一 `sessionId` 并发调用会由会话锁自动串行化，无需手动加锁。

### 使用 Plan-and-Execute Agent

```java
import com.agent.core.agent.plan.PlanAndExecuteAgent;

PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
    llmClient, registry,
    10,    // maxSteps
    true   // enableReplanning
);
AgentResult result = agent.run("研究 AI 趋势并写一段 200 字摘要");
```

### 创建自定义工具

#### 方法 1：注解方式（推荐）

```java
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

public class WeatherTools {
    @Tool(name = "get_weather", description = "获取城市当前天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city) {
        return String.format("%s 天气：22°C，晴天", city);
    }
}

AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);
processor.register(new WeatherTools());
```

#### 方法 2：实现 Tool 接口（必须返回 ToolResult）

> 注意：`Tool.execute` 的返回类型是 `ToolResult`，**不再是 `String`**。返回值表达「成功 / 不可重试失败 / 可重试失败」，便于模型自纠与 `ToolRegistry` 分发错误回调。

```java
import com.agent.core.tool.*;

public class MyTool implements Tool {
    @Override public ToolDefinition getDefinition() {
        return ToolDefinition.simple("my_tool", "描述", "param1", "参数说明");
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        Object v = args.get("param1");
        if (v == null) return ToolResult.retryable("缺少参数 param1"); // 模型可修正重试
        return ToolResult.success("结果: " + v);
    }

    // 可选：声明风险等级。越权(如网络)用 SENSITIVE；可执行任意命令/代码用 DANGEROUS
    // @Override public RiskLevel riskLevel() { return RiskLevel.SENSITIVE; }
    // 可选：自定义超时
    // @Override public Duration timeout() { return Duration.ofSeconds(30); }
}
registry.register(new MyTool());
```

### 危险工具必须显式开启

`CommandExecutionTool`（执行任意 shell 命令）为 `DANGEROUS`。`ToolRegistry` 出于安全默认拒绝注册，需显式 opt-in：

```java
ToolRegistry registry = new ToolRegistry();
registry.setAllowDangerousTools(true);   // 仅在输入可信、进程本身已沙箱时开启
registry.register(new CommandExecutionTool());
```

### 切换记忆后端（内存 / Redis / MySQL）

```java
// 内存（默认）
agent.setMemoryFactory(id -> new InMemoryStore());

// Redis：每个 session 一个 key，TTL 秒
agent.setMemoryFactory(id -> new RedisMemory("localhost", 6379, "agent:session:" + id, 3600));

// MySQL：同 URL+用户共享一个 HikariCP 连接池
agent.setMemoryFactory(id -> new MySQLMemory(jdbcUrl, "root", "root", "agent_messages", id));
```

## 可观察性与日志

框架通过 `AgentObserver` 接口提供可观察性。快速启用：

```java
import com.agent.core.observer.LoggingObserver;

LoggingObserver observer = new LoggingObserver(true); // verbose 打印完整内容
llmClient.setObserver(observer);
registry.setObserver(observer);
agent.setObserver(observer);
```

回调事件：
- LLM 调用：`onLLMCallStart` / `onLLMCallEnd` / `onLLMCallError`
- 工具执行：`onToolCallStart` / `onToolCallEnd` / `onToolCallError`
- Agent 步骤：`onStepStart` / `onStepEnd`

所有回调带耗时（毫秒）；置 `null` 可关闭。

## Agent 模式

### React Agent
1. **Thought**：LLM 推理当前情况 → 2. **Action**：决定调用工具 → 3. **Observation**：观察结果 → 循环至最终答案。适用于需多次工具调用的交互任务。

### Plan-and-Execute Agent
PLAN → EXECUTE（每步一个小 ReAct 循环）→ REPLAN（可选，有界）→ SYNTHESIZE（LLM 汇总为最终答案）。适用于需前期规划的多步任务。

## 内置工具与风险等级

| 工具 | 名称 | 说明 | 风险 |
| --- | --- | --- | --- |
| `CalculatorTool` | `calculator` | 数学表达式求值 | SAFE |
| `DateTimeTool` | `get_datetime` | 当前时间 | SAFE |
| `WebFetchTool` | `web_fetch` | http(s) 抓取（经 `UrlGuard` 防 SSRF） | SENSITIVE |
| `FileReadTool` | `file_read` | 读文本/行区间（`PathSandbox` 限定） | SAFE |
| `FileListTool` | `file_list` | 列目录 + glob + 深度 | SAFE |
| `FileSearchTool` | `file_search` | 目录内 grep | SAFE |
| `FileWriteTool` | `file_write` | 写文件（沙箱 + 大小上限） | SENSITIVE |
| `FileEditTool` | `file_edit` | 精确替换文本（唯一匹配） | SENSITIVE |
| `CommandExecutionTool` | `command_execute` | 执行 shell 命令 | DANGEROUS（默认禁） |

## 维护指南

- **加 LLM 提供商**：实现 `LLMClient` 接口，或为 `OpenAILLMClient` 复用 OpenAI 兼容端点。
- **扩展存储**：继承 `AbstractMemory`，实现五个 `do*` 原语（`doAdd/doGetMessages/doClear/doSize/doReplaceAll`）即可。
- **日志**：SLF4J + Logback，配置见 `src/main/resources/logback.xml`。

## 依赖项

- **OpenAI Java SDK 4.52.0**：OpenAI/兼容端点客户端
- **Jackson 2.17.0**：JSON 处理
- **SLF4J 2.0.12** / **Logback 1.5.3**：日志
- **Jedis 5.1.2**：Redis
- **MySQL Connector/J 8.3.0** + **HikariCP 5.1.0**：MySQL
- **JUnit 5.10.2**：测试
