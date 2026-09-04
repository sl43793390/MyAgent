package com.agent.core.memory;

import com.agent.core.model.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 基于 {@link ArrayList} 的进程内对话存储。
 *
 * <p>适用于单进程部署、测试以及短时会话。对话在重启后不会保留；
 * 若需要保留，请改用 {@link RedisMemory} 或 {@link MySQLMemory}。
 *
 * <p>线程安全：对底层列表的每次访问都进行了同步。
 */
public class InMemoryStore extends AbstractMemory {

    private final List<Message> messages = new ArrayList<>();

    /** 使用默认压缩阈值创建存储。 */
    public InMemoryStore() {
        this(DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * 使用自定义压缩阈值创建存储。
     *
     * @param compressionTokenThreshold 触发压缩的 Token 阈值
     */
    public InMemoryStore(long compressionTokenThreshold) {
        super(compressionTokenThreshold);
    }

    /**
     * 使用自定义压缩阈值与 Token 估算器创建存储。
     */
    public InMemoryStore(long compressionTokenThreshold, TokenEstimator tokenEstimator) {
        super(compressionTokenThreshold, tokenEstimator);
    }

    @Override
    protected synchronized void doAdd(Message message) {
        messages.add(message);
    }

    @Override
    protected synchronized List<Message> doGetMessages() {
        return new ArrayList<>(messages);
    }

    @Override
    protected synchronized void doClear() {
        messages.clear();
    }

    @Override
    protected synchronized int doSize() {
        return messages.size();
    }

    @Override
    protected synchronized void doReplaceAll(List<Message> replacement) {
        messages.clear();
        messages.addAll(replacement);
    }
}
