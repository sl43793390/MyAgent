package com.agent.core.observer;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.model.ToolCall;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 使用 SLF4J 记录全部事件的默认观察者实现。
 *
 * <p>日志策略：
 * <ul>
 *   <li>每个生命周期事件都以简洁的单行摘要记录在 INFO 级别。</li>
 *   <li>当开启 {@code verbose} 时，完整的消息内容、工具参数与工具结果记录在
 *       DEBUG 级别（需要将该 logger 的级别设为 DEBUG）。</li>
 *   <li>错误会连同完整堆栈信息一起记录。</li>
 *   <li>智能体步骤事件会包含本观察者测得的步骤耗时，
 *       因此 {@link AgentObserver} 的实现无需改动即可获得耗时数据。</li>
 * </ul>
 *
 * <p>本类线程安全：步骤计时按线程分别记录，因此同一观察者实例上并发运行的
 * 多个智能体互不干扰。
 */
public class LoggingObserver implements AgentObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingObserver.class);

    private static final int VERBOSE_MESSAGE_LENGTH = 500;
    private static final int VERBOSE_CONTENT_LENGTH = 1000;

    private final boolean verbose;

    /** (线程 ID, 阶段, 步骤) -> 步骤开始时的 nanoTime；用于记录步骤耗时。 */
    private final Map<String, Long> stepStartTimes = new ConcurrentHashMap<>();

    /**
     * 创建一个日志观察者。
     *
     * @param verbose 若为真，还会在 DEBUG 级别记录消息内容、工具参数与结果
     */
    public LoggingObserver(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * 创建一个非详细模式的日志观察者。
     */
    public LoggingObserver() {
        this(false);
    }

    @Override
    public void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {
        log.info("[LLM] Call start: {} message(s) [{}], {} tool(s) [{}]",
                messages.size(), roleSummary(messages), tools.size(), toolNames(tools));

        if (verbose) {
            for (int i = 0; i < messages.size(); i++) {
                log.debug("[LLM] Message[{}]: {}", i, describe(messages.get(i)));
            }
        }
    }

    @Override
    public void onLLMCallEnd(LLMResponse response, long duration) {
        log.info("[LLM] Call end in {}ms: finishReason={}, tokens={} (prompt={}, completion={}), id={}, model={}",
                duration, response.finishReason(), response.totalTokens(),
                response.promptTokens(), response.completionTokens(), response.id(), response.model());

        long cachedTokens = response.usage().cachedPromptTokens();
        long reasoningTokens = response.usage().reasoningTokens();
        if (cachedTokens > 0) {
            log.info("[LLM] Usage detail: cachedPromptTokens={}", cachedTokens);
        }
        if (reasoningTokens > 0) {
            log.info("[LLM] Usage detail: reasoningTokens={}", reasoningTokens);
        }

        if (response.hasToolCalls()) {
            List<ToolCall> toolCalls = response.toolCalls();
            log.info("[LLM] Requested {} tool call(s): {}", toolCalls.size(), describeToolCalls(toolCalls));
        }

        if (verbose && response.content() != null) {
            log.debug("[LLM] Response content: {}", truncate(response.content(), VERBOSE_CONTENT_LENGTH));
        }
    }

    @Override
    public void onLLMCallError(Exception error, long duration) {
        log.error("[LLM] Call failed after {}ms: {}", duration, error.getMessage(), error);
    }

    @Override
    public void onToolCallStart(String toolName, String arguments) {
        log.info("[Tool] Call start: {}", toolName);
        if (verbose && arguments != null) {
            log.debug("[Tool] Arguments: {}", truncate(arguments, VERBOSE_MESSAGE_LENGTH));
        }
    }

    @Override
    public void onToolCallEnd(String toolName, String result, long duration) {
        log.info("[Tool] Call end in {}ms: {}", duration, toolName);
        if (verbose && result != null) {
            log.debug("[Tool] Result: {}", truncate(result, VERBOSE_CONTENT_LENGTH));
        }
    }

    @Override
    public void onToolCallError(String toolName, Exception error, long duration) {
        log.error("[Tool] Call failed after {}ms: {}", duration, toolName, error);
    }

    @Override
    public void onStepStart(int stepNumber, String phase) {
        stepStartTimes.put(stepKey(stepNumber, phase), System.nanoTime());
        log.info("[Agent] Step {} start (phase: {})", stepNumber, phase);
    }

    @Override
    public void onStepEnd(int stepNumber, String phase) {
        Long startNanos = stepStartTimes.remove(stepKey(stepNumber, phase));
        if (startNanos != null) {
            log.info("[Agent] Step {} end (phase: {}, duration: {}ms)",
                    stepNumber, phase, (System.nanoTime() - startNanos) / 1_000_000);
        } else {
            log.info("[Agent] Step {} end (phase: {})", stepNumber, phase);
        }
    }

    private String stepKey(int stepNumber, String phase) {
        return Thread.currentThread().threadId() + ":" + phase + ":" + stepNumber;
    }

    private String roleSummary(List<Message> messages) {
        return messages.stream()
                .collect(Collectors.groupingBy(
                        message -> message.role().name().toLowerCase(),
                        LinkedHashMap::new,
                        Collectors.counting()))
                .entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
    }

    private String toolNames(List<ToolDefinition> tools) {
        return tools.stream()
                .map(ToolDefinition::name)
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String describeToolCalls(List<ToolCall> toolCalls) {
        return toolCalls.stream()
                .map(toolCall -> toolCall.name() + "(" + toolCall.id() + ")")
                .collect(Collectors.joining(", ", "[", "]"));
    }

    private String describe(Message message) {
        StringBuilder sb = new StringBuilder("role=").append(message.role());
        if (message.content() != null) {
            sb.append(", content=").append(truncate(message.content(), VERBOSE_MESSAGE_LENGTH));
        }
        if (message.hasToolCalls()) {
            sb.append(", toolCalls=").append(describeToolCalls(message.toolCalls()));
        }
        if (message.toolCallId() != null) {
            sb.append(", toolCallId=").append(message.toolCallId());
        }
        return sb.toString();
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
