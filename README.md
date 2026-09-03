# Java Agent Framework (my-agent)

A Java 21 agent framework implementing **React Agent** and **Plan-and-Execute Agent** patterns for building AI-powered applications. It is shipped as a **library** (`com.agent:my-agent:1.0.0`) with no standalone `main` class; runnable demos live under `src/test`.

## Features

- **React Agent** — a bounded Thought → Action → Observation loop with per-session serialisation and a run budget.
- **Plan-and-Execute Agent** — an explicit state machine (Plan → Execute → Replan → Synthesize) that genuinely honours the session id.
- **Tool system** — an extensible `ToolRegistry` with per-tool timeout and risk-level gating; 10 built-in tools.
- **Annotation tools** — create tools with `@Tool` / `@ToolParam` (Spring AI style).
- **Memory management** — `Memory` + `AbstractMemory` base class over in-memory / Redis / MySQL backends, switchable in one line, with automatic threshold compression.
- **Security boundaries** — `PathSandbox` (file sandbox), `UrlGuard` (anti-SSRF), `ToolPolicy` (capability admission); dangerous tools are refused by default.
- **Observability** — `AgentObserver` for LLM calls, tool executions and agent steps.
- **Session management** — `SessionManager` with TTL, capacity bound, LRU eviction and per-session locks.
- **OpenAI SDK** — official OpenAI Java SDK (4.52.0), works against any OpenAI-compatible endpoint.
- **Java 21** — records, text blocks, switch expressions.

## Architecture

```
src/main/java/com/agent/
└── core/
    ├── agent/
    │   ├── BaseAgent.java                 # abstract base: sessions / budget / compression
    │   ├── AgentResult.java  RunBudget.java
    │   ├── react/ReactAgent.java
    │   └── plan/PlanAndExecuteAgent.java
    ├── llm/
    │   ├── LLMClient.java  LLMParams.java
    │   ├── OpenAILLMClient.java  LLMException.java
    ├── memory/
    │   ├── Memory.java  AbstractMemory.java  MemoryFactory.java
    │   ├── InMemoryStore.java  RedisMemory.java  MySQLMemory.java
    │   ├── MemoryCompressor.java  TokenEstimator.java  JsonSupport.java
    ├── model/   Message.java  Role.java  ToolCall.java  LLMResponse.java  TokenUsage.java
    ├── observer/ AgentObserver.java  LoggingObserver.java
    ├── security/ PathSandbox.java  UrlGuard.java  ToolPolicy.java
    ├── session/ SessionManager.java
    └── tool/
        ├── Tool.java  ToolDefinition.java  ToolResult.java  RiskLevel.java  ToolRegistry.java
        ├── annotation/ Tool.java(@Tool) ToolParam.java AnnotationToolProcessor.java AnnotatedMethodTool.java
        └── builtin/ CalculatorTool DateTimeTool WebFetchTool
                     FileReadTool FileWriteTool FileEditTool FileListTool FileSearchTool
                     CommandExecutionTool Args.java
src/test/java/com/agent/test/
    AgentDemoTests.java  WeatherTools.java  CurrencyTools.java  MemorySwitchDemo.java
pom.xml
```

> Earlier README referenced `example/ExampleApp.java` and `com.agent.App` — those do not exist; this module is a library.

## Prerequisites

- Java 21 or higher
- Maven 3.8+
- An OpenAI API key (or any OpenAI-compatible endpoint: DeepSeek / GLM / vLLM / Ollama / One-API)

## Build & run demos

```bash
cd d:\ideaSpace\MyAgent
mvn clean package
```

The framework has no `java -jar` entry point. To run demos (requires `OPENAI_API_KEY`):

```bash
# run the in-memory / Redis / MySQL memory-switching demo
mvn compile exec:java -Dexec.mainClass="com.agent.test.MemorySwitchDemo" -Dexec.classpathScope=test
```

## Configuration

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key-here
# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key-here"
# optional custom gateway
# export OPENAI_BASE_URL=https://your-gateway/v1
```

## Usage

### React Agent

```java
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CalculatorTool;

LLMClient llmClient = OpenAILLMClient.openAI(apiKey, "gpt-4o-mini"); // or openAI(apiKey, baseUrl, model)

ToolRegistry registry = new ToolRegistry();
registry.register(new CalculatorTool());

ReactAgent agent = new ReactAgent(llmClient, registry, 10); // max iterations

AgentResult result = agent.run("Calculate (15 + 27) * 3");   // stateless
System.out.println(result.output());

String sessionId = "user-123";                               // stateful multi-turn
agent.run("calculate 15*27", sessionId);
AgentResult r2 = agent.run("what was the previous result?", sessionId);
```

Pass a `sessionId` for memory across turns; concurrent calls sharing one session id are serialised automatically by the session lock.

### Plan-and-Execute Agent

```java
import com.agent.core.agent.plan.PlanAndExecuteAgent;

PlanAndExecuteAgent agent = new PlanAndExecuteAgent(llmClient, registry, 10, true); // steps, replan
AgentResult result = agent.run("Research AI trends and write a 200-word summary");
```

### Custom tools

#### Method 1 — annotation (recommended)

```java
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

public class WeatherTools {
    @Tool(name = "get_weather", description = "Get current weather for a city")
    public String getWeather(@ToolParam(name = "city", description = "City name") String city) {
        return String.format("Weather in %s: 22°C, Sunny", city);
    }
}
new AnnotationToolProcessor(registry).register(new WeatherTools());
```

#### Method 2 — implement `Tool` (must return `ToolResult`)

> `Tool.execute` returns a **`ToolResult`**, not a `String`. Use it to express success vs. permanent failure vs. retryable failure so the model can correct itself and the registry can fire error callbacks.

```java
import com.agent.core.tool.*;

public class MyTool implements Tool {
    @Override public ToolDefinition getDefinition() {
        return ToolDefinition.simple("my_tool", "Description", "param1", "Param description");
    }

    @Override public ToolResult execute(Map<String, Object> args) {
        Object v = args.get("param1");
        if (v == null) return ToolResult.retryable("Missing param1"); // model may fix & retry
        return ToolResult.success("Result: " + v);
    }
    // optional: @Override public RiskLevel riskLevel() { return RiskLevel.SENSITIVE; }
    // optional: @Override public Duration timeout() { return Duration.ofSeconds(30); }
}
registry.register(new MyTool());
```

### Dangerous tools require explicit opt-in

`CommandExecutionTool` is `DANGEROUS` (arbitrary shell execution) and is refused by the registry unless enabled:

```java
ToolRegistry registry = new ToolRegistry();
registry.setAllowDangerousTools(true);   // only with trusted input / sandboxed process
registry.register(new CommandExecutionTool());
```

### Switch memory backend

```java
agent.setMemoryFactory(id -> new InMemoryStore());                                          // in-memory (default)
agent.setMemoryFactory(id -> new RedisMemory("localhost", 6379, "agent:session:" + id, 3600)); // Redis, TTL seconds
agent.setMemoryFactory(id -> new MySQLMemory(jdbcUrl, "root", "root", "agent_messages", id)); // MySQL (shared pool per url+user)
```

## Observability

```java
import com.agent.core.observer.LoggingObserver;
LoggingObserver observer = new LoggingObserver(true); // verbose
llmClient.setObserver(observer);
registry.setObserver(observer);
agent.setObserver(observer);
```

Callbacks: `onLLMCallStart/End/Error`, `onToolCallStart/End/Error`, `onStepStart/End` — all with durations; pass `null` to disable.

## Agent patterns

- **React Agent**: Thought → Action (tool) → Observation, repeated until a final answer. Best for interactive, multi-tool tasks.
- **Plan-and-Execute Agent**: PLAN → EXECUTE (each step a mini ReAct loop) → REPLAN (bounded, optional) → SYNTHESIZE (LLM produces the final answer). Best for complex multi-step tasks.

## Built-in tools & risk levels

| Tool | Name | Description | Risk |
| --- | --- | --- | --- |
| `CalculatorTool` | `calculator` | evaluate a math expression | SAFE |
| `DateTimeTool` | `get_datetime` | current date/time | SAFE |
| `WebFetchTool` | `web_fetch` | http(s) fetch via `UrlGuard` (anti-SSRF) | SENSITIVE |
| `FileReadTool` | `file_read` | read file / line range (sandboxed) | SAFE |
| `FileListTool` | `file_list` | list directory + glob + depth | SAFE |
| `FileSearchTool` | `file_search` | grep inside a directory | SAFE |
| `FileWriteTool` | `file_write` | write a file (sandbox + size cap) | SENSITIVE |
| `FileEditTool` | `file_edit` | unique-exact-string edit | SENSITIVE |
| `CommandExecutionTool` | `command_execute` | run a shell command | DANGEROUS (opt-in) |

## Maintenance

- **New LLM provider**: implement `LLMClient`, or reuse `OpenAILLMClient` against an OpenAI-compatible endpoint.
- **New storage**: extend `AbstractMemory` and implement the five `do*` primitives (`doAdd/doGetMessages/doClear/doSize/doReplaceAll`).
- **Logging**: SLF4J + Logback, configured in `src/main/resources/logback.xml`.

## Dependencies

- **OpenAI Java SDK 4.52.0**
- **Jackson 2.17.0**
- **SLF4J 2.0.12** / **Logback 1.5.3**
- **Jedis 5.1.2**
- **MySQL Connector/J 8.3.0** + **HikariCP 5.1.0**
- **JUnit 5.10.2**
