package com.agent.core.observer;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.tool.ToolDefinition;

import java.util.List;

/**
 * Observer interface for monitoring agent execution at various stages.
 *
 * Implement this interface to add custom logging, tracing, metrics, or debugging
 * at key points in the agent lifecycle.
 *
 * Usage:
 * <pre>
 * {@code
 * AgentObserver observer = new AgentObserver() {
 *     @Override
 *     public void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {
 *         System.out.println("About to call LLM with " + messages.size() + " messages");
 *     }
 * };
 *
 * // Register on LLM client
 * llmClient.setObserver(observer);
 *
 * // Register on tool registry
 * toolRegistry.setObserver(observer);
 * }
 * </pre>
 */
public interface AgentObserver {

    /**
     * Called before sending a request to the LLM.
     *
     * @param messages the conversation messages being sent
     * @param tools    the tool definitions being provided
     */
    default void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {}

    /**
     * Called after receiving a response from the LLM.
     *
     * @param response the LLM response
     * @param duration the call duration in milliseconds
     */
    default void onLLMCallEnd(LLMResponse response, long duration) {}

    /**
     * Called when the LLM call fails.
     *
     * @param error    the exception that occurred
     * @param duration the call duration in milliseconds
     */
    default void onLLMCallError(Exception error, long duration) {}

    /**
     * Called before executing a tool.
     *
     * @param toolName  the name of the tool
     * @param arguments the JSON arguments string
     */
    default void onToolCallStart(String toolName, String arguments) {}

    /**
     * Called after a tool has been executed successfully.
     *
     * @param toolName the name of the tool
     * @param result   the tool execution result
     * @param duration the execution duration in milliseconds
     */
    default void onToolCallEnd(String toolName, String result, long duration) {}

    /**
     * Called when a tool execution fails.
     *
     * @param toolName the name of the tool
     * @param error    the exception that occurred
     * @param duration the execution duration in milliseconds
     */
    default void onToolCallError(String toolName, Exception error, long duration) {}

    /**
     * Called when an agent step starts.
     *
     * @param stepNumber the current step number
     * @param phase      the current phase (e.g., "react", "plan", "execute", "replan")
     */
    default void onStepStart(int stepNumber, String phase) {}

    /**
     * Called when an agent step ends.
     *
     * @param stepNumber the current step number
     * @param phase      the current phase
     */
    default void onStepEnd(int stepNumber, String phase) {}
}
