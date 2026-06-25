package com.agent.core.observer;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.model.ToolCall;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * A default observer implementation that logs all events using SLF4J.
 *
 * This can be used directly or extended for custom behavior.
 */
public class LoggingObserver implements AgentObserver {

    private static final Logger log = LoggerFactory.getLogger(LoggingObserver.class);

    private final boolean verbose;

    /**
     * Create a logging observer.
     *
     * @param verbose if true, log full message content and tool arguments/results
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
        log.info("[LLM] Calling LLM with {} message(s), {} tool(s)",
                messages.size(), tools.size());

        if (verbose) {
            for (int i = 0; i < messages.size(); i++) {
                Message msg = messages.get(i);
                log.debug("[LLM] Message[{}]: role={}, content={}",
                        i, msg.role(), truncate(msg.content(), 500));
            }
            if (!tools.isEmpty()) {
                log.debug("[LLM] Tools: {}", tools.stream().map(ToolDefinition::name).toList());
            }
        }
    }

    @Override
    public void onLLMCallEnd(LLMResponse response, long duration) {
        String content = response.content();
        boolean hasToolCalls = response.hasToolCalls();

        log.info("[LLM] Response received in {}ms, tokens: {} (prompt={}, completion={})",
                duration, response.totalTokens(), response.promptTokens(), response.completionTokens());

        if (hasToolCalls) {
            List<ToolCall> toolCalls = response.toolCalls();
            log.info("[LLM] LLM requested {} tool call(s): {}",
                    toolCalls.size(),
                    toolCalls.stream().map(ToolCall::name).toList());
        } else {
            log.info("[LLM] LLM provided final answer");
        }

        if (verbose && content != null) {
            log.debug("[LLM] Response content: {}", truncate(content, 1000));
        }
    }

    @Override
    public void onLLMCallError(Exception error, long duration) {
        log.error("[LLM] LLM call failed after {}ms: {}", duration, error.getMessage());
    }

    @Override
    public void onToolCallStart(String toolName, String arguments) {
        log.info("[Tool] Executing tool: {}", toolName);
        if (verbose) {
            log.debug("[Tool] Arguments: {}", truncate(arguments, 500));
        }
    }

    @Override
    public void onToolCallEnd(String toolName, String result, long duration) {
        log.info("[Tool] Tool '{}' completed in {}ms", toolName, duration);
        if (verbose) {
            log.debug("[Tool] Result: {}", truncate(result, 1000));
        }
    }

    @Override
    public void onToolCallError(String toolName, Exception error, long duration) {
        log.error("[Tool] Tool '{}' failed after {}ms: {}", toolName, duration, error.getMessage());
    }

    @Override
    public void onStepStart(int stepNumber, String phase) {
        log.info("[Agent] Step {} started (phase: {})", stepNumber, phase);
    }

    @Override
    public void onStepEnd(int stepNumber, String phase) {
        log.info("[Agent] Step {} completed (phase: {})", stepNumber, phase);
    }

    private String truncate(String text, int maxLength) {
        if (text == null) return "null";
        return text.length() > maxLength ? text.substring(0, maxLength) + "..." : text;
    }
}
