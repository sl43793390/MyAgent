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
 * A default observer implementation that logs all events using SLF4J.
 *
 * <p>Logging strategy:
 * <ul>
 *   <li>Concise one-line summaries for every lifecycle event are logged at INFO.</li>
 *   <li>Full message content, tool arguments and tool results are logged at DEBUG
 *       when {@code verbose} is enabled (requires DEBUG level on this logger).</li>
 *   <li>Errors are logged with the full stack trace.</li>
 *   <li>Agent step events include the step duration measured by this observer,
 *       so {@link AgentObserver} implementations don't need to change to get timing.</li>
 * </ul>
 *
 * <p>This class is thread-safe: step timings are tracked per thread, so concurrent
 * agent runs on the same observer instance do not interfere.
 */
public class LoggingObserver implements AgentObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingObserver.class);

    private static final int VERBOSE_MESSAGE_LENGTH = 500;
    private static final int VERBOSE_CONTENT_LENGTH = 1000;

    private final boolean verbose;

    /** (threadId, phase, step) -> nanoTime when the step started; enables step duration logging. */
    private final Map<String, Long> stepStartTimes = new ConcurrentHashMap<>();

    /**
     * Create a logging observer.
     *
     * @param verbose if true, also log message content, tool arguments and results at DEBUG level
     */
    public LoggingObserver(boolean verbose) {
        this.verbose = verbose;
    }

    /**
     * Create a non-verbose logging observer.
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
