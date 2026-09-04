package com.agent.core.model;

/**
 * 表示 LLM 请求的一次工具调用。
 *
 * @param id        本次工具调用的唯一标识
 * @param name      要调用的工具名称
 * @param arguments 传给工具的参数（JSON 字符串）
 */
public record ToolCall(
        String id,
        String name,
        String arguments
) {
}
