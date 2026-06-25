# Java Agent 框架

一个基于 Java 21 的 Agent 框架，实现了 React Agent 和 Plan-and-Execute Agent 模式，用于构建 AI 驱动的应用程序。

## 特性

- **React Agent**: 实现推理 + 行动模式，包含 Thought → Action → Observation 循环
- **Plan-and-Execute Agent**: 两阶段方法，包含规划和执行阶段
- **工具系统**: 可扩展的工具注册表，内置工具（计算器、日期时间、网页抓取）
- **注解式工具**: 使用 `@Tool` 和 `@ToolParam` 注解创建工具（类似 Spring AI）
- **内存管理**: 支持多种后端的对话历史管理（内存、Redis、MySQL）
- **可观察性**: 内置观察者接口，用于监控 LLM 调用、工具执行和 Agent 步骤
- **OpenAI SDK**: 使用官方 OpenAI Java SDK (4.32.0) 进行可靠的 API 集成
- **Java 21**: 利用现代 Java 特性实现简洁高效的代码

## 架构

```
java-agent/
├── src/main/java/com/agent/
│   ├── core/
│   │   ├── agent/
│   │   │   ├── BaseAgent.java              # 基础 Agent 类
│   │   │   ├── AgentResult.java            # Agent 执行结果
│   │   │   ├── react/
│   │   │   │   └── ReactAgent.java         # React Agent 实现
│   │   │   └── plan/
│   │   │       └── PlanAndExecuteAgent.java # Plan-and-Execute Agent
│   │   ├── llm/
│   │   │   ├── LLMClient.java              # LLM 客户端接口
│   │   │   └── OpenAILLMClient.java        # OpenAI SDK 实现
│   │   ├── memory/
│   │   │   ├── Memory.java                 # 内存接口
│   │   │   ├── InMemoryStore.java          # 内存实现
│   │   │   ├── RedisMemory.java            # Redis 实现
│   │   │   └── MySQLMemory.java            # MySQL 实现
│   │   ├── model/
│   │   │   ├── Message.java                # 消息模型
│   │   │   ├── Role.java                   # 消息角色
│   │   │   ├── ToolCall.java               # 工具调用模型
│   │   │   └── LLMResponse.java            # LLM 响应模型
│   │   ├── observer/
│   │   │   ├── AgentObserver.java          # 观察者接口
│   │   │   └── LoggingObserver.java        # 日志实现
│   │   └── tool/
│   │       ├── Tool.java                   # 工具接口
│   │       ├── ToolDefinition.java         # 工具定义
│   │       ├── ToolRegistry.java           # 工具注册表
│   │       ├── annotation/                 # 注解式工具
│   │       │   ├── Tool.java               # @Tool 注解
│   │       │   ├── ToolParam.java          # @ToolParam 注解
│   │       │   ├── AnnotationToolProcessor.java
│   │       │   └── AnnotatedMethodTool.java
│   │       └── builtin/                    # 内置工具
│   │           ├── CalculatorTool.java
│   │           ├── DateTimeTool.java
│   │           └── WebFetchTool.java
│   └── example/
│       └── ExampleApp.java                 # 示例应用
├── src/test/java/com/agent/test/
│   └── AgentDemoTests.java                 # 演示测试
└── pom.xml
```

## 前置要求

- Java 21 或更高版本
- Maven 3.8+
- OpenAI API 密钥（或兼容 API）

## 安装

1. 克隆或进入项目目录：

```bash
cd d:\tareSpace\MyAgent
```

2. 构建项目：

```bash
mvn clean package
```

## 配置

将 OpenAI API 密钥设置为环境变量：

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key-here

# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key-here"
```

## 使用方法

### 运行示例

```bash
mvn exec:java -Dexec.mainClass="com.agent.App"
```

或运行打包的 JAR：

```bash
java -jar target/java-agent-1.0.0.jar
```

### 使用 React Agent

```java
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CalculatorTool;

// 初始化 LLM 客户端
LLMClient llmClient = OpenAILLMClient.openAI(apiKey, "gpt-4o-mini");

// 注册工具
ToolRegistry toolRegistry = new ToolRegistry();
toolRegistry.register(new CalculatorTool());

// 创建 React Agent
ReactAgent agent = new ReactAgent(llmClient, toolRegistry, 10);

// 运行 agent
AgentResult result = agent.run("计算 (15 + 27) * 3");
System.out.println(result.output());
```

### 使用 Plan-and-Execute Agent

```java
import com.agent.core.agent.plan.PlanAndExecuteAgent;

// 创建 Plan-and-Execute Agent
PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
    llmClient,
    toolRegistry,
    10,      // 最大步骤数
    true     // 启用重新规划
);

// 运行 agent
AgentResult result = agent.run("研究 AI 趋势并创建摘要");
System.out.println(result.output());
```

### 创建自定义工具

#### 方法 1：注解方式（推荐）

使用 `@Tool` 和 `@ToolParam` 注解简化工具创建：

```java
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

public class WeatherTools {
    @Tool(name = "get_weather", description = "获取城市当前天气")
    public String getWeather(
            @ToolParam(name = "city", description = "城市名称") String city
    ) {
        return String.format("%s 天气：22°C，晴天", city);
    }
}

// 注册工具
AnnotationToolProcessor processor = new AnnotationToolProcessor(toolRegistry);
processor.register(new WeatherTools());
```

#### 方法 2：实现 Tool 接口

```java
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import java.util.Map;

public class MyCustomTool implements Tool {
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.simple(
            "my_tool",
            "工具功能描述",
            "param1",
            "参数描述"
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String param1 = (String) arguments.get("param1");
        // 实现工具逻辑
        return "结果: " + param1;
    }
}

// 注册工具
toolRegistry.register(new MyCustomTool());
```

## 可观察性与日志

框架通过 `AgentObserver` 接口提供全面的可观察性系统，允许你在每个阶段监控和调试 Agent 执行。

### 启用日志

#### 快速开始：使用内置 LoggingObserver

```java
import com.agent.core.observer.LoggingObserver;

// 创建观察者（verbose 模式显示完整消息内容）
LoggingObserver observer = new LoggingObserver(true);

// 附加到组件
llmClient.setObserver(observer);
toolRegistry.setObserver(observer);
agent.setObserver(observer);

// 运行 agent - 所有事件都会被记录
AgentResult result = agent.run("你的任务");
```

#### 自定义观察者实现

```java
import com.agent.core.observer.AgentObserver;
import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.tool.ToolDefinition;
import java.util.List;

public class MyObserver implements AgentObserver {
    @Override
    public void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {
        System.out.println("LLM 调用开始，消息数：" + messages.size());
    }

    @Override
    public void onLLMCallEnd(LLMResponse response, long duration) {
        System.out.println("LLM 响应时间：" + duration + "ms");
        System.out.println("使用 token 数：" + response.totalTokens());
    }

    @Override
    public void onToolCallStart(String toolName, String arguments) {
        System.out.println("执行工具：" + toolName);
    }

    @Override
    public void onToolCallEnd(String toolName, String result, long duration) {
        System.out.println("工具 " + toolName + " 完成，耗时：" + duration + "ms");
    }

    @Override
    public void onStepStart(int stepNumber, String phase) {
        System.out.println("步骤 " + stepNumber + " 开始（阶段：" + phase + "）");
    }

    @Override
    public void onStepEnd(int stepNumber, String phase) {
        System.out.println("步骤 " + stepNumber + " 完成");
    }
}

// 使用自定义观察者
MyObserver observer = new MyObserver();
agent.setObserver(observer);
```

### 禁用日志

要禁用可观察性，只需将观察者设置为 `null`：

```java
// 在所有组件上禁用观察者
llmClient.setObserver(null);
toolRegistry.setObserver(null);
agent.setObserver(null);
```

### Logback 配置

框架使用 SLF4J 和 Logback。在 `src/main/resources/logback.xml` 中配置日志：

```xml
<configuration>
    <!-- 控制台输出 -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 文件输出（带滚动策略） -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/agent.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>logs/agent-%d{yyyy-MM-dd}.%i.log</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>3GB</totalSizeCap>
        </rollingPolicy>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 控制日志级别 -->
    <logger name="com.agent" level="DEBUG"/>
    <logger name="com.agent.core.llm" level="INFO"/>
    <logger name="com.agent.core.tool" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### 观察者事件

`AgentObserver` 接口提供以下回调：

- **LLM 调用**：`onLLMCallStart`、`onLLMCallEnd`、`onLLMCallError`
- **工具执行**：`onToolCallStart`、`onToolCallEnd`、`onToolCallError`
- **Agent 步骤**：`onStepStart`、`onStepEnd`

所有回调都包含时间信息（毫秒数），用于性能监控。

## Agent 模式

### React Agent

React（推理 + 行动）模式实现一个循环：

1. **Thought**: LLM 推理当前情况
2. **Action**: LLM 决定使用工具
3. **Observation**: 观察工具结果
4. 重复直到得出最终答案

**适用场景**: 需要多次工具调用和迭代推理的交互式任务。

### Plan-and-Execute Agent

两阶段方法：

1. **规划阶段**: LLM 创建分步计划
2. **执行阶段**: 顺序执行每个步骤
3. **重新规划**（可选）: 根据结果修订计划

**适用场景**: 需要前期规划的复杂多步骤任务。

## 内置工具

- **CalculatorTool**: 计算数学表达式
- **DateTimeTool**: 获取当前日期和时间
- **WebFetchTool**: 从 URL 获取内容

## 维护指南

### 添加新的 LLM 提供商

实现 `LLMClient` 接口：

```java
public class MyLLMClient implements LLMClient {
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        // 实现对你的 LLM 提供商的 API 调用
    }
}
```

### 扩展内存管理

实现 `Memory` 接口以自定义存储：

```java
public class DatabaseMemory implements Memory {
    @Override
    public void add(Message message) {
        // 存储到数据库
    }
    
    @Override
    public List<Message> getMessages() {
        // 从数据库检索
    }
    
    // ... 其他方法
}
```

### 日志配置

框架使用 SLF4J 和 Logback。在 `src/main/resources/logback.xml` 中配置日志。

## 依赖项

- **OpenAI Java SDK 4.32.0**: 官方 OpenAI API 客户端
- **Jackson 2.17.0**: JSON 处理
- **SLF4J 2.0.12**: 日志门面
- **Logback 1.5.3**: 日志实现
- **Jedis 5.1.2**: Redis 客户端
- **MySQL Connector 8.3.0**: MySQL 驱动
- **HikariCP 5.1.0**: 数据库连接池
- **JUnit 5.10.2**: 测试框架

