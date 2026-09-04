package com.agent.core.llm;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.observer.AgentObserver;
import com.agent.core.tool.ToolDefinition;

import java.util.List;

/**
 * 与大模型交互的接口。
 */
public interface LLMClient {

    /**
     * 向大模型发送消息并获取回复。
     *
     * @param messages 对话历史
     * @param tools    可供大模型使用的工具
     * @return 大模型的回复
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /**
     * 向大模型发送消息并携带自定义参数获取回复。
     *
     * @param messages 对话历史
     * @param tools    可供大模型使用的工具
     * @param params   自定义的大模型参数（temperature、topP 等）
     * @return 大模型的回复
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, LLMParams params);

    /**
     * 向大模型发送消息（不使用工具）。
     *
     * @param messages 对话历史
     * @return 大模型的回复
     */
    default LLMResponse chat(List<Message> messages) {
        return chat(messages, List.of());
    }

    /**
     * 设置用于监控大模型调用的观察者。
     *
     * @param observer 观察者，传 null 表示禁用
     */
    void setObserver(AgentObserver observer);

    /**
     * 获取当前的观察者。
     *
     * @return 当前的观察者，若未设置则为 null
     */
    AgentObserver getObserver();
}
