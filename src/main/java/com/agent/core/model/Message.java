package com.agent.core.model;

import java.util.List;

/**
 * Represents a message in a conversation with the LLM.
 *
 * @param role       the role of the message sender
 * @param content    the text content of the message
 * @param toolCallId the tool call ID (for tool role messages)
 * @param name       the tool name (for tool role messages)
 * @param toolCalls  list of tool calls requested by the assistant
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
