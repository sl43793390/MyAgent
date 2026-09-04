package com.agent.core.agent;

import com.agent.core.model.Message;
import com.agent.core.model.TokenUsage;

import java.util.List;

/**
 * 智能体一次执行的执行结果。
 *
 * @param output       最终输出的文本
 * @param totalSteps   智能体所采取的步骤数
 * @param messages     本次运行的完整消息列表（对话记录）
 * @param model        生成响应的模型，由 provider 上报（未知时为 null）
 * @param finishReason 最终 LLM 响应的结束原因，例如 "stop"、"length"（未知时为 null）
 * @param llmCallCount 本次运行期间发起的 LLM 调用次数
 * @param usage        本次运行所有 LLM 调用累计的 token 用量
 */
public record AgentResult(
        String output,
        int totalSteps,
        List<Message> messages,
        String model,
        String finishReason,
        int llmCallCount,
        TokenUsage usage
) {

    public AgentResult {
        messages = messages != null ? List.copyOf(messages) : List.of();
        usage = usage != null ? usage : TokenUsage.NONE;
    }

    /**
     * 本次运行消耗的输入（提示词）Token 总数。
     */
    public int promptTokens() {
        return (int) usage.promptTokens();
    }

    /**
     * 本次运行生成的输出（补全）Token 总数。
     */
    public int completionTokens() {
        return (int) usage.completionTokens();
    }

    /**
     * 本次运行消耗的 Token 总数（输入 + 输出）。
     */
    public int totalTokens() {
        return (int) usage.totalTokens();
    }
}
