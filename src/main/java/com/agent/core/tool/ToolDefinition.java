package com.agent.core.tool;

import java.util.Map;

/**
 * 可供 LLM 调用的工具的定义。
 *
 * @param name        工具的唯一名称
 * @param description 对工具功能的可读描述
 * @param parameters  描述工具参数的 JSON Schema
 */
public record ToolDefinition(
        String name,
        String description,
        Map<String, Object> parameters
) {

    /**
     * 创建一个带单个字符串参数的简单工具定义。
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
     * 创建一个不带参数的工具定义。
     */
    public static ToolDefinition noArgs(String name, String description) {
        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", Map.of()
        );
        return new ToolDefinition(name, description, parameters);
    }
}
