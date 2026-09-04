package com.agent.core.memory;

import com.agent.core.model.Message;

import java.util.List;

/**
 * 单个会话的存储。
 *
 * <p>本接口刻意只关注<b>存储</b>。早期版本还承载了
 * {@code setCompressionLLMClient(...)} 和 {@code setCompressionPrompt(...)}，导致每个
 * 存储后端都依赖 LLM 层——基于 Redis 的存储不得不了解它几乎永远用不到的聊天客户端，
 * 除非有人碰巧调用了 {@code compress()}。摘要逻辑现已移至
 * {@link MemoryCompressor}，并由 {@link AbstractMemory} 统一编排。
 *
 * <h3>约定</h3>
 * <ul>
 *   <li>实现必须保证同一会话的多个线程可安全并发访问。</li>
 *   <li>{@link #getMessages()} 返回一个不可变快照，消息按从旧到新排列。</li>
 *   <li>排序必须稳定且完全有序：先后写入的两条消息绝不能出现顺序歧义，否则服务商将拒绝
 *       该会话（例如某条工具结果排在了它对应的工具调用之前）。</li>
 *   <li>持有资源（连接池、客户端）的实现应当重写
 *       {@link #close()}；{@link com.agent.core.session.SessionManager} 会在会话被
 *       淘汰或管理器关闭时调用该方法。</li>
 * </ul>
 *
 * <p>请实现 {@link AbstractMemory} 而非直接实现本接口——它提供了 Token 统计、
 * 阈值触发的压缩以及防御性拷贝。
 */
public interface Memory {

    /**
     * 会话自动压缩的默认 Token 阈值（100,000 个 Token）。
     */
    long DEFAULT_COMPRESSION_TOKEN_THRESHOLD = 100000L;

    /**
     * 在会话末尾追加一条消息。
     *
     * @param message 消息；为 null 时忽略
     */
    void add(Message message);

    /**
     * 全部消息，从旧到新。
     *
     * @return 不可变快照
     */
    List<Message> getMessages();

    /**
     * 移除全部消息。
     */
    void clear();

    /**
     * 已存储消息的数量。
     */
    int size();

    /**
     * 当前已存储内容的 Token 估算总量。
     */
    long estimateTokens();

    /**
     * 将历史记录重写为一条简短摘要，并保留全部系统消息。
     *
     * @return 历史被重写时返回 true；没有可压缩内容或未挂载
     *         {@link MemoryCompressor} 时返回 false
     */
    boolean compress();

    /**
     * 释放本存储持有的资源。对于不持有资源的存储，默认实现为空操作。
     */
    default void close() {
    }
}
