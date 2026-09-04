package com.agent.core.memory;

import com.agent.core.model.Message;
import com.agent.core.model.ToolCall;

import java.util.List;

/**
 * 估算一条消息消耗的 Token 数量。
 *
 * <p>精确计数需要特定供应商的分词器；而智能体框架需要一种廉价的方案，
 * 能在每条消息上运行且无需网络调用。本接口让估算可插拔，
 * 这样应用就可以接入真正的分词器（例如通过 jtokkit），而无需改动
 * 内存相关的实现。
 *
 * <p>内置的 {@link #heuristic()} 是一种基于字符类别的启发式算法：CJK 字符每个约消耗
 * 1.5 个 Token，其余每个约 0.25 个（约每 4 个字符一个 Token），再加上每条消息
 * 固定开销，用于角色、分隔符与标识符。
 */
@FunctionalInterface
public interface TokenEstimator {

    /** 每条消息的角色、分隔符、工具调用 id 与名称的固定开销。 */
    int MESSAGE_OVERHEAD = 4;

    /**
     * 估算单条消息的 Token 开销。
     */
    long estimate(Message message);

    /**
     * 估算一整段对话的 Token 总开销。
     */
    default long estimateAll(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (Message message : messages) {
            total += estimate(message);
        }
        return total;
    }

    /**
     * 内置的基于字符类别的启发式算法。无状态且线程安全；返回的是一个共享实例。
     */
    static TokenEstimator heuristic() {
        return Heuristic.INSTANCE;
    }

    /**
     * 基于字符类别的启发式算法：CJK 字符约 1.5 Token/字符，其余字符约 0.25 Token/字符，
     * 每条消息另加 {@link #MESSAGE_OVERHEAD}。
     */
    final class Heuristic implements TokenEstimator {

        static final Heuristic INSTANCE = new Heuristic();

        private static final double CJK_TOKENS_PER_CHAR = 1.5;
        private static final double OTHER_TOKENS_PER_CHAR = 0.25;

        private Heuristic() {}

        @Override
        public long estimate(Message message) {
            if (message == null) {
                return 0L;
            }
            long tokens = MESSAGE_OVERHEAD;
            tokens += estimateText(message.content());
            tokens += estimateText(message.name());
            tokens += estimateText(message.toolCallId());
            if (message.toolCalls() != null) {
                for (ToolCall toolCall : message.toolCalls()) {
                    tokens += estimateText(toolCall.name());
                    tokens += estimateText(toolCall.arguments());
                }
            }
            return tokens;
        }

        private long estimateText(String text) {
            if (text == null || text.isEmpty()) {
                return 0L;
            }
            long cjkChars = 0L;
            for (int i = 0; i < text.length(); i++) {
                if (Character.UnicodeScript.of(text.charAt(i)) == Character.UnicodeScript.HAN) {
                    cjkChars++;
                }
            }
            long otherChars = text.length() - cjkChars;
            return (long) (cjkChars * CJK_TOKENS_PER_CHAR + otherChars * OTHER_TOKENS_PER_CHAR);
        }
    }
}
