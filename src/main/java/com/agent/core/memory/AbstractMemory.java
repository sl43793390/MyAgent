package com.agent.core.memory;

import com.agent.core.model.Message;
import com.agent.core.model.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link Memory} 各实现共享的行为：Token 统计、阈值触发的压缩以及防御性拷贝。
 *
 * <p>在本类出现之前，{@code InMemoryStore}、{@code RedisMemory} 和 {@code MySQLMemory}
 * 各自重复实现了 Token 估算、缓存惰性初始化、自动压缩与摘要重建——约 150 行代码被复制了三份，
 * 且细节逐渐产生分歧。现在子类只需实现五个存储原语，其余行为全部继承：
 *
 * <pre>{@code
 * protected void      doAdd(Message message)        // 持久化一条消息
 * protected List<Message> doGetMessages()           // 读取全部消息，从旧到新
 * protected void      doClear()
 * protected int       doSize()
 * protected void      doReplaceAll(List<Message>)   // 原子性地替换整个历史记录
 * }</pre>
 *
 * <p>压缩工作委托给 {@link MemoryCompressor}，由持有 LLM 客户端的智能体注入。
 * 未配置压缩器的记忆对象永远不会执行压缩，从而使存储层完全不依赖 LLM 层。
 *
 * <p>生命周期方法（{@link #add}、{@link #getMessages}、{@link #clear}、{@link #size}、
 * {@link #estimateTokens}、{@link #compress}）均为 {@code final}：子类应通过实现
 * {@code do*} 系列原语来扩展行为，而不是重写算法本身。
 */
public abstract class AbstractMemory implements Memory {

    private static final Logger log = LoggerFactory.getLogger(AbstractMemory.class);

    /** 哨兵值，表示"Token 缓存需要重新计算"。 */
    private static final long TOKENS_UNKNOWN = -1L;

    private final long compressionTokenThreshold;
    private final TokenEstimator tokenEstimator;
    private volatile MemoryCompressor compressor;
    private long cachedTokens = TOKENS_UNKNOWN;

    protected AbstractMemory(long compressionTokenThreshold) {
        this(compressionTokenThreshold, TokenEstimator.heuristic());
    }

    protected AbstractMemory(long compressionTokenThreshold, TokenEstimator tokenEstimator) {
        this.compressionTokenThreshold = compressionTokenThreshold > 0
                ? compressionTokenThreshold
                : DEFAULT_COMPRESSION_TOKEN_THRESHOLD;
        this.tokenEstimator = tokenEstimator != null ? tokenEstimator : TokenEstimator.heuristic();
    }

    // ---------------------------------------------------------------- 存储原语

    /** 持久化单条消息。由 {@link #add(Message)} 在判空之后调用。 */
    protected abstract void doAdd(Message message);

    /** 读取全部已存储的消息，从旧到新。 */
    protected abstract List<Message> doGetMessages();

    /** 移除全部已存储的消息。 */
    protected abstract void doClear();

    /** 已存储消息的数量。 */
    protected abstract int doSize();

    /**
     * 用 {@code messages} 原子性地替换整个历史记录。
     *
     * <p>在压缩之后调用。若底层存储不支持多语句事务，实现至少应让替换过程尽可能短暂。
     */
    protected abstract void doReplaceAll(List<Message> messages);

    // ---------------------------------------------------------------- Memory 契约

    @Override
    public final void add(Message message) {
        if (message == null) {
            return;
        }
        doAdd(message);
        if (cachedTokens != TOKENS_UNKNOWN) {
            cachedTokens += estimate(message);
        }
        maybeCompress();
    }

    @Override
    public final List<Message> getMessages() {
        return List.copyOf(doGetMessages());
    }

    @Override
    public final void clear() {
        doClear();
        cachedTokens = 0L;
    }

    @Override
    public final int size() {
        return doSize();
    }

    @Override
    public final long estimateTokens() {
        if (cachedTokens == TOKENS_UNKNOWN) {
            cachedTokens = tokenEstimator.estimateAll(doGetMessages());
        }
        return cachedTokens;
    }

    /**
     * 将历史记录压缩为一条摘要。
     *
     * <p>系统消息会原样保留；其余内容——包括此前已生成的摘要，以免它们不断累积——
     * 都会被合并为一条新的摘要消息。
     *
     * @return 历史被重写时返回 true
     */
    @Override
    public final synchronized boolean compress() {
        MemoryCompressor activeCompressor = compressor;
        if (activeCompressor == null) {
            log.debug("No MemoryCompressor configured on {}; skipping compression",
                    getClass().getSimpleName());
            return false;
        }

        List<Message> systemMessages = new ArrayList<>();
        List<Message> conversation = new ArrayList<>();
        for (Message message : doGetMessages()) {
            if (message.role() == Role.SYSTEM && !MemoryCompressor.isSummary(message)) {
                systemMessages.add(message);
            } else {
                conversation.add(message);
            }
        }
        if (conversation.isEmpty()) {
            return false;
        }

        String summary = activeCompressor.summarize(conversation);
        if (summary == null || summary.isBlank()) {
            return false;
        }

        List<Message> compacted = new ArrayList<>(systemMessages);
        compacted.add(Message.system(MemoryCompressor.SUMMARY_PREFIX + summary));

        doReplaceAll(compacted);
        invalidateTokenCache();
        log.info("Compressed {} message(s) into a summary ({} remain)",
                conversation.size(), compacted.size());
        return true;
    }

    // ---------------------------------------------------------------- 配置

    /**
     * 设置供 {@link #compress()} 以及阈值触发式压缩所使用的压缩器。
     *
     * <p>通常由智能体在把记忆对象绑定到会话时调用：智能体持有 LLM 客户端，
     * 这样可以让 {@link Memory} 契约本身不依赖任何 LLM 类型。
     *
     * @param compressor 压缩器；传入 null 表示禁用压缩
     */
    public final void setCompressor(MemoryCompressor compressor) {
        this.compressor = compressor;
    }

    /**
     * 当前已挂载的压缩器；若压缩被禁用则为 null。
     */
    public final MemoryCompressor compressor() {
        return compressor;
    }

    /**
     * 触发 {@link #compress()} 自动执行的 Token 阈值。
     */
    public final long compressionTokenThreshold() {
        return compressionTokenThreshold;
    }

    /**
     * 用于 Token 统计的估算器。
     */
    public final TokenEstimator tokenEstimator() {
        return tokenEstimator;
    }

    // ---------------------------------------------------------------- 内部实现

    /**
     * 强制在下一次访问时重新计算 Token 估算值。子类在 {@link #add} 与 {@link #clear}
     * 之外修改存储时（例如 {@link #doReplaceAll}）必须调用本方法。
     */
    protected final void invalidateTokenCache() {
        cachedTokens = TOKENS_UNKNOWN;
    }

    protected final long estimate(Message message) {
        return tokenEstimator.estimate(message);
    }

    private void maybeCompress() {
        MemoryCompressor activeCompressor = compressor;
        if (activeCompressor == null || compressionTokenThreshold <= 0) {
            return;
        }
        if (estimateTokens() < compressionTokenThreshold) {
            return;
        }
        log.info("Estimated tokens {} reached threshold {}; compressing",
                estimateTokens(), compressionTokenThreshold);
        compress();
    }
}
