# Java Agent Framework

A Java 21 agent framework implementing React Agent and Plan-and-Execute Agent patterns for building AI-powered applications.

## Features

- **React Agent**: Implements the Reasoning + Acting pattern with Thought → Action → Observation loop
- **Plan-and-Execute Agent**: Two-phase approach with planning and execution stages
- **Tool System**: Extensible tool registry with built-in tools (calculator, datetime, web fetch)
- **Annotation-based Tools**: Create tools using `@Tool` and `@ToolParam` annotations (Spring AI style)
- **Memory Management**: Conversation history management with multiple backends (in-memory, Redis, MySQL)
- **Observability**: Built-in observer interface for monitoring LLM calls, tool executions, and agent steps
- **OpenAI SDK**: Uses official OpenAI Java SDK (4.32.0) for reliable API integration
- **Java 21**: Leverages modern Java features for clean, efficient code

## Architecture

```
java-agent/
├── src/main/java/com/agent/
│   ├── core/
│   │   ├── agent/
│   │   │   ├── BaseAgent.java              # Base agent class
│   │   │   ├── AgentResult.java            # Agent execution result
│   │   │   ├── react/
│   │   │   │   └── ReactAgent.java         # React Agent implementation
│   │   │   └── plan/
│   │   │       └── PlanAndExecuteAgent.java # Plan-and-Execute Agent
│   │   ├── llm/
│   │   │   ├── LLMClient.java              # LLM client interface
│   │   │   └── OpenAILLMClient.java        # OpenAI SDK implementation
│   │   ├── memory/
│   │   │   ├── Memory.java                 # Memory interface
│   │   │   ├── InMemoryStore.java          # In-memory implementation
│   │   │   ├── RedisMemory.java            # Redis implementation
│   │   │   └── MySQLMemory.java            # MySQL implementation
│   │   ├── model/
│   │   │   ├── Message.java                # Message model
│   │   │   ├── Role.java                   # Message roles
│   │   │   ├── ToolCall.java               # Tool call model
│   │   │   └── LLMResponse.java            # LLM response model
│   │   ├── observer/
│   │   │   ├── AgentObserver.java          # Observer interface
│   │   │   └── LoggingObserver.java        # Logging implementation
│   │   └── tool/
│   │       ├── Tool.java                   # Tool interface
│   │       ├── ToolDefinition.java         # Tool definition
│   │       ├── ToolRegistry.java           # Tool registry
│   │       ├── annotation/                 # Annotation-based tools
│   │       │   ├── Tool.java               # @Tool annotation
│   │       │   ├── ToolParam.java          # @ToolParam annotation
│   │       │   ├── AnnotationToolProcessor.java
│   │       │   └── AnnotatedMethodTool.java
│   │       └── builtin/                    # Built-in tools
│   │           ├── CalculatorTool.java
│   │           ├── DateTimeTool.java
│   │           └── WebFetchTool.java
│   └── example/
│       └── ExampleApp.java                 # Example application
├── src/test/java/com/agent/test/
│   └── AgentDemoTests.java                 # Demo tests
└── pom.xml
```

## Prerequisites

- Java 21 or higher
- Maven 3.8+
- OpenAI API key (or compatible API)

## Installation

1. Clone or navigate to the project directory:

```bash
cd d:\tareSpace\MyAgent
```

2. Build the project:

```bash
mvn clean package
```

## Configuration

Set your OpenAI API key as an environment variable:

```bash
# Linux/Mac
export OPENAI_API_KEY=your-api-key-here

# Windows PowerShell
$env:OPENAI_API_KEY="your-api-key-here"
```

## Usage

### Running the Example

```bash
mvn exec:java -Dexec.mainClass="com.agent.App"
```

Or run the packaged JAR:

```bash
java -jar target/java-agent-1.0.0.jar
```

### Using React Agent

```java
import com.agent.core.agent.react.ReactAgent;
import com.agent.core.llm.OpenAILLMClient;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.tool.builtin.CalculatorTool;

// Initialize LLM client
LLMClient llmClient = OpenAILLMClient.openAI(apiKey, "gpt-4o-mini");

// Register tools
ToolRegistry toolRegistry = new ToolRegistry();
toolRegistry.register(new CalculatorTool());

// Create React Agent
ReactAgent agent = new ReactAgent(llmClient, toolRegistry, 10);

// Run agent
AgentResult result = agent.run("Calculate (15 + 27) * 3");
System.out.println(result.output());
```

### Using Plan-and-Execute Agent

```java
import com.agent.core.agent.plan.PlanAndExecuteAgent;

// Create Plan-and-Execute Agent
PlanAndExecuteAgent agent = new PlanAndExecuteAgent(
    llmClient,
    toolRegistry,
    10,      // max steps
    true     // enable replanning
);

// Run agent
AgentResult result = agent.run("Research AI trends and create a summary");
System.out.println(result.output());
```

### Creating Custom Tools

#### Method 1: Annotation-based (Recommended)

Use `@Tool` and `@ToolParam` annotations for simpler tool creation:

```java
import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

public class WeatherTools {
    @Tool(name = "get_weather", description = "Get current weather for a city")
    public String getWeather(
            @ToolParam(name = "city", description = "City name") String city
    ) {
        return String.format("Weather in %s: 22°C, Sunny", city);
    }
}

// Register the tool
AnnotationToolProcessor processor = new AnnotationToolProcessor(toolRegistry);
processor.register(new WeatherTools());
```

#### Method 2: Implement Tool Interface

```java
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import java.util.Map;

public class MyCustomTool implements Tool {
    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.simple(
            "my_tool",
            "Description of what the tool does",
            "param1",
            "Description of parameter"
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String param1 = (String) arguments.get("param1");
        // Implement tool logic
        return "Result: " + param1;
    }
}

// Register the tool
toolRegistry.register(new MyCustomTool());
```

## Observability and Logging

The framework provides a comprehensive observability system through the `AgentObserver` interface, allowing you to monitor and debug agent execution at every stage.

### Enabling Logging

#### Quick Start: Use Built-in LoggingObserver

```java
import com.agent.core.observer.LoggingObserver;

// Create observer (verbose mode shows full message content)
LoggingObserver observer = new LoggingObserver(true);

// Attach to components
llmClient.setObserver(observer);
toolRegistry.setObserver(observer);
agent.setObserver(observer);

// Run agent - all events will be logged
AgentResult result = agent.run("Your task here");
```

#### Custom Observer Implementation

```java
import com.agent.core.observer.AgentObserver;
import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.tool.ToolDefinition;
import java.util.List;

public class MyObserver implements AgentObserver {
    @Override
    public void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {
        System.out.println("LLM call starting with " + messages.size() + " messages");
    }

    @Override
    public void onLLMCallEnd(LLMResponse response, long duration) {
        System.out.println("LLM responded in " + duration + "ms");
        System.out.println("Tokens used: " + response.totalTokens());
    }

    @Override
    public void onToolCallStart(String toolName, String arguments) {
        System.out.println("Executing tool: " + toolName);
    }

    @Override
    public void onToolCallEnd(String toolName, String result, long duration) {
        System.out.println("Tool " + toolName + " completed in " + duration + "ms");
    }

    @Override
    public void onStepStart(int stepNumber, String phase) {
        System.out.println("Step " + stepNumber + " started (phase: " + phase + ")");
    }

    @Override
    public void onStepEnd(int stepNumber, String phase) {
        System.out.println("Step " + stepNumber + " completed");
    }
}

// Use custom observer
MyObserver observer = new MyObserver();
agent.setObserver(observer);
```

### Disabling Logging

To disable observability, simply set the observer to `null`:

```java
// Disable observer on all components
llmClient.setObserver(null);
toolRegistry.setObserver(null);
agent.setObserver(null);
```

### Logback Configuration

The framework uses SLF4J with Logback. Configure logging in `src/main/resources/logback.xml`:

```xml
<configuration>
    <!-- Console output -->
    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- File output with rolling policy -->
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

    <!-- Control log levels -->
    <logger name="com.agent" level="DEBUG"/>
    <logger name="com.agent.core.llm" level="INFO"/>
    <logger name="com.agent.core.tool" level="INFO"/>

    <root level="INFO">
        <appender-ref ref="STDOUT"/>
        <appender-ref ref="FILE"/>
    </root>
</configuration>
```

### Observer Events

The `AgentObserver` interface provides callbacks for:

- **LLM Calls**: `onLLMCallStart`, `onLLMCallEnd`, `onLLMCallError`
- **Tool Executions**: `onToolCallStart`, `onToolCallEnd`, `onToolCallError`
- **Agent Steps**: `onStepStart`, `onStepEnd`

All callbacks include timing information (duration in milliseconds) for performance monitoring.

## Agent Patterns

### React Agent

The React (Reasoning + Acting) pattern implements a loop:

1. **Thought**: LLM reasons about the current situation
2. **Action**: LLM decides to use a tool
3. **Observation**: Tool result is observed
4. Repeat until final answer

Best for: Interactive tasks requiring multiple tool calls and iterative reasoning.

### Plan-and-Execute Agent

Two-phase approach:

1. **Planning Phase**: LLM creates a step-by-step plan
2. **Execution Phase**: Each step is executed sequentially
3. **Replanning** (optional): Plan can be revised based on results

Best for: Complex, multi-step tasks where upfront planning is beneficial.

## Built-in Tools

- **CalculatorTool**: Evaluates mathematical expressions
- **DateTimeTool**: Gets current date and time
- **WebFetchTool**: Fetches content from URLs

## Maintenance

### Adding New LLM Providers

Implement the `LLMClient` interface:

```java
public class MyLLMClient implements LLMClient {
    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        // Implement API call to your LLM provider
    }
}
```

### Extending Memory

Implement the `Memory` interface for custom storage:

```java
public class DatabaseMemory implements Memory {
    @Override
    public void add(Message message) {
        // Store in database
    }
    
    @Override
    public List<Message> getMessages() {
        // Retrieve from database
    }
    
    // ... other methods
}
```

### Logging

The framework uses SLF4J with Logback. Configure logging in `src/main/resources/logback.xml`.

## Dependencies

- **OpenAI Java SDK 4.32.0**: Official OpenAI API client
- **Jackson 2.17.0**: JSON processing
- **SLF4J 2.0.12**: Logging facade
- **Logback 1.5.3**: Logging implementation
- **Jedis 5.1.2**: Redis client
- **MySQL Connector 8.3.0**: MySQL driver
- **HikariCP 5.1.0**: Database connection pool
- **JUnit 5.10.2**: Testing framework

