package com.agent.core.observer;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.tool.ToolDefinition;

import java.util.List;

/**
 * 用于在各阶段监控智能体执行的观察者接口。
 *
 * 实现该接口以在智能体生命周期的关键节点添加自定义日志、链路追踪、指标采集或调试。
 *
 * 用法：
 * <pre>
 * {@code
 * AgentObserver observer = new AgentObserver() {
 *     @Override
 *     public void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {
 *         System.out.println("About to call LLM with " + messages.size() + " messages");
 *     }
 * };
 *
 * // 注册到 LLM 客户端
 * llmClient.setObserver(observer);
 *
 * // 注册到工具注册表
 * toolRegistry.setObserver(observer);
 * }
 * </pre>
 */
public interface AgentObserver {

    /**
     * 在向后端 LLM 发送请求之前调用。
     *
     * @param messages 待发送的对话消息
     * @param tools    提供的工具定义
     */
    default void onLLMCallStart(List<Message> messages, List<ToolDefinition> tools) {}

    /**
     * 在收到 LLM 响应之后调用。
     *
     * @param response LLM 响应
     * @param duration 调用耗时（毫秒）
     */
    default void onLLMCallEnd(LLMResponse response, long duration) {}

    /**
     * 在 LLM 调用失败时调用。
     *
     * @param error   发生的异常
     * @param duration 调用耗时（毫秒）
     */
    default void onLLMCallError(Exception error, long duration) {}

    /**
     * 在执行工具之前调用。
     *
     * @param toolName  工具名称
     * @param arguments 工具参数的 JSON 字符串
     */
    default void onToolCallStart(String toolName, String arguments) {}

    /**
     * 在工具成功执行之后调用。
     *
     * @param toolName 工具名称
     * @param result   工具执行结果
     * @param duration 执行耗时（毫秒）
     */
    default void onToolCallEnd(String toolName, String result, long duration) {}

    /**
     * 在工具执行失败时调用。
     *
     * @param toolName 工具名称
     * @param error    发生的异常
     * @param duration 执行耗时（毫秒）
     */
    default void onToolCallError(String toolName, Exception error, long duration) {}

    /**
     * 在智能体某个步骤开始时调用。
     *
     * @param stepNumber 当前步骤编号
     * @param phase      当前阶段（例如 "react"、"plan"、"execute"、"replan"）
     */
    default void onStepStart(int stepNumber, String phase) {}

    /**
     * 在智能体某个步骤结束时调用。
     *
     * @param stepNumber 当前步骤编号
     * @param phase      当前阶段
     */
    default void onStepEnd(int stepNumber, String phase) {}
}
