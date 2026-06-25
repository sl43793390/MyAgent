package com.agent.core.tool;

import java.util.Map;

/**
 * Interface for tools that can be executed by the agent.
 */
public interface Tool {

    /**
     * Get the tool definition.
     */
    ToolDefinition getDefinition();

    /**
     * Execute the tool with the given arguments.
     *
     * @param arguments the arguments as a map
     * @return the result of the tool execution
     */
    String execute(Map<String, Object> arguments);
}
