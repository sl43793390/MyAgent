package com.agent.core.tool;

import java.util.Map;

/**
 * Definition of a tool that can be used by the LLM.
 *
 * @param name        unique name of the tool
 * @param description human-readable description of what the tool does
 * @param parameters  JSON Schema describing the tool's parameters
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {

    /**
     * Create a simple tool definition with a single string parameter.
     */
    public static ToolDefinition simple(String name, String description, String parameterName, String parameterDescription) {
        Map<String, Object> properties = Map.of(
                parameterName, Map.of(
                        "type", "string",
                        "description", parameterDescription
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{parameterName}
        );

        return new ToolDefinition(name, description, parameters);
    }

    /**
     * Create a tool definition with no parameters.
     */
    public static ToolDefinition noArgs(String name, String description) {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of()
        );
        return new ToolDefinition(name, description, parameters);
    }
}
