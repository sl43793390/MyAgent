package com.agent.core.model;

import java.util.List;

/**
 * 表示与 LLM 对话中的一条消息。
 *
 * @param role       消息发送者的角色
 * @param content    消息的文本内容
 * @param toolCallId 工具调用 ID（用于 tool 角色的消息）
 * @param name       工具名称（用于 tool 角色的消息）
 * @param toolCalls  助手请求的工具调用列表
 */
public record Message(
        Role role,
        String content,
        String toolCallId,
        String name,
        List<ToolCall> toolCalls
) {

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null, null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null, null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null, null, null);
    }

    public static Message assistant(List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, null, null, null, toolCalls);
    }

    public static Message tool(String content, String toolCallId, String name) {
        return new Message(Role.TOOL, content, toolCallId, name, null);
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }
}
