package com.agent.core.model;

/**
 * Represents a tool call requested by the LLM.
 *
 * @param id       unique identifier for this tool call
 * @param name     name of the tool to invoke
 * @param arguments JSON string of arguments to pass to the tool
 */
public record ToolCall(
        String id,
        String name,
        String arguments
) {
}
