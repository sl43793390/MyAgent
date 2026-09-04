package com.agent.core.session;

import com.agent.core.memory.Memory;
import com.agent.core.memory.MemoryFactory;
import com.agent.core.model.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 持有每个活跃会话的对话上下文。
 *
 * <h3>为何它不止是一个 Map</h3>
 * <p>之前的实现是一个裸的 {@code ConcurrentHashMap}，它会无限增长，从不释放任何东西。由此引发了三个问题：
 * <ul>
 *   <li><b>无界内存。</b> 每个出现过的会话及其完整记录都会常驻内存。一个对外暴露的智能体将一直泄漏，直到 JVM 崩溃。</li>
 *   <li><b>连接泄漏。</b> 不做任何淘汰也就意味着不关闭任何东西，因此持有连接池引用的
 *       {@code Memory} 会永远保留该引用。</li>
 *   <li><b>无可排斥性（互斥）。</b> 对同一会话的两个并发请求会交错它们的消息，
 *       导致一段对话里工具结果出现在请求它的工具调用之前——而这类对话会被服务商直接拒绝。</li>
 * </ul>
 *
 * <p>本版本新增了 TTL、容量上限、LRU 淘汰、淘汰时的资源释放，以及按会话的锁。
 * 过期采用访问时惰性评估（开销低，无需后台线程），并保证至少在每
 * 个 {@code purgeInterval} 或容量超限时执行一次。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * SessionManager sessions = new SessionManager(MemoryFactory.inMemory());
 *
 * // 针对同一会话串行化整个回合，使并发请求无法交错。
 * sessions.withSessionLock("user-123", () -> {
 *     Memory context = sessions.getOrCreate("user-123", systemPrompt);
 *     context.add(Message.user("Hello"));
 *     return agent.runWith(context, "Hello");
 * });
 * }</pre>
 *
 * <p>本类是线程安全的。
 */
public class SessionManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    /** 空闲会话的默认存活时长（TTL）：2 小时。 */
    public static final Duration DEFAULT_TTL = Duration.ofHours(2);

    /** 活跃会话的默认最大数量：1,000。 */
    public static final int DEFAULT_MAX_SESSIONS = 1000;

    /** 过期清理扫描之间的最小默认间隔：1 分钟。 */
    public static final Duration DEFAULT_PURGE_INTERVAL = Duration.ofMinutes(1);

    private final ConcurrentMap<String, Memory> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> lastAccessNanos = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

    private final long ttlNanos;
    private final long purgeIntervalNanos;
    private final int maxSessions;
    private final ReentrantLock purgeLock = new ReentrantLock();

    private volatile MemoryFactory memoryFactory;
    private volatile long lastPurgeNanos = System.nanoTime();
    private volatile boolean closed = false;

    /** 创建使用内存会话并采用全部默认值的会话管理器。 */
    public SessionManager() {
        this(MemoryFactory.inMemory());
    }

    /** 使用自定义的 {@link MemoryFactory} 并采用全部默认值来创建管理器。 */
    public SessionManager(MemoryFactory memoryFactory) {
        this(memoryFactory, DEFAULT_TTL, DEFAULT_MAX_SESSIONS, DEFAULT_PURGE_INTERVAL);
    }

    /**
     * 创建一个可完全控制会话生命周期与容量的会话管理器。
     *
     * @param memoryFactory 为每个新会话生成 {@link Memory} 的工厂
     * @param ttl           空闲会话的存活时长；{@link Duration#ZERO} 表示禁用过期
     * @param maxSessions   最大活跃会话数；超过该值后最久未使用的会话会被淘汰。必须为正数。
     * @param purgeInterval 两次过期清理之间的最小间隔
     */
    public SessionManager(MemoryFactory memoryFactory, Duration ttl,
                          int maxSessions, Duration purgeInterval) {
        this.memoryFactory = memoryFactory != null ? memoryFactory : MemoryFactory.inMemory();
        this.ttlNanos = ttl == null || ttl.isNegative() ? DEFAULT_TTL.toNanos() : ttl.toNanos();
        this.maxSessions = maxSessions > 0 ? maxSessions : DEFAULT_MAX_SESSIONS;
        this.purgeIntervalNanos = purgeInterval == null || purgeInterval.isNegative()
                ? DEFAULT_PURGE_INTERVAL.toNanos()
                : purgeInterval.toNanos();
    }

    /**
     * 使用自定义压缩阈值创建管理器（旧版便捷构造器）。
     *
     * @param compressionTokenThreshold 应用于新内存会话的 token 阈值
     */
    public SessionManager(long compressionTokenThreshold) {
        this(MemoryFactory.inMemory(compressionTokenThreshold));
    }

    /**
     * 替换用于新会话的 {@link MemoryFactory}。已有会话不受影响。
     *
     * @param memoryFactory 新的工厂；为 null 时回退到内存存储
     */
    public void setMemoryFactory(MemoryFactory memoryFactory) {
        this.memoryFactory = memoryFactory != null ? memoryFactory : MemoryFactory.inMemory();
    }

    /**
     * 当前用于新会话的工厂。
     */
    public MemoryFactory getMemoryFactory() {
        return memoryFactory;
    }

    /**
     * 获取或创建某个会话的上下文。
     *
     * @param sessionId    会话的唯一标识符（用户 id、对话 id 等）
     * @param systemPrompt 用于初始化新建会话的系统提示词
     * @return 该会话的记忆
     * @throws IllegalStateException 如果管理器已关闭
     */
    public Memory getOrCreate(String sessionId, String systemPrompt) {
        requireOpen();
        String id = requireSessionId(sessionId);
        MemoryFactory factory = this.memoryFactory;

        Memory memory = sessions.computeIfAbsent(id, key -> {
            Memory created = factory.create(key);
            if (created == null) {
                throw new IllegalStateException("MemoryFactory returned null for session '" + key + "'");
            }
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                created.add(Message.system(systemPrompt));
            }
            return created;
        });

        touch(id);
        purgeIfNeeded();
        return memory;
    }

    /**
     * 在不创建新会话的前提下获取已有的会话上下文。
     *
     * @return 记忆；当会话不存在时为 null
     */
    public Memory get(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Memory memory = sessions.get(sessionId);
        if (memory != null) {
            touch(sessionId);
        }
        return memory;
    }

    /**
     * 判断某个会话是否存在。
     */
    public boolean exists(String sessionId) {
        return sessionId != null && sessions.containsKey(sessionId);
    }

    /**
     * 丢弃某个会话，并释放其 {@link Memory} 持有的资源。
     */
    public void clear(String sessionId) {
        if (sessionId != null) {
            evict(sessionId);
        }
    }

    /**
     * 丢弃全部会话并释放其资源。
     */
    public void clearAll() {
        for (String sessionId : List.copyOf(sessions.keySet())) {
            evict(sessionId);
        }
    }

    /**
     * 活跃会话的数量（在当前调用的任何过期清理扫描之前）。
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }

    /**
     * 在持有单个会话锁的情况下运行某个操作。
     *
     * <p>一个智能体回合会多次触碰会话（追加用户消息、调用 LLM、追加工具结果）。若无互斥，
     * 同一会话上的两个并发回合会交错这些追加，从而破坏对话。使用本方法可使整个回合具备原子性：
     *
     * <pre>{@code
     * sessions.withSessionLock(sessionId, () -> agent.run(input, sessionId));
     * }</pre>
     *
     * @param sessionId 要锁定的会话
     * @param action    在锁保护下运行的工作
     * @return {@code action} 的返回值
     */
    public <T> T withSessionLock(String sessionId, Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        ReentrantLock lock = sessionLocks.computeIfAbsent(requireSessionId(sessionId),
                key -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 丢弃全部会话并拒绝后续使用。幂等。
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clearAll();
        log.info("SessionManager closed");
    }

    /**
     * 判断 {@link #close()} 是否已被调用。
     */
    public boolean isClosed() {
        return closed;
    }

    // ---------------------------------------------------------------- 内部实现

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("SessionManager is closed");
        }
    }

    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        return sessionId;
    }

    private void touch(String sessionId) {
        lastAccessNanos.put(sessionId, System.nanoTime());
    }

    /**
     * 清理已过期会话，并在超出容量时淘汰最近最少使用的会话。
     * 除非容量已超限，否则最多每隔 {@code purgeIntervalNanos} 运行一次；若某个线程发现清理锁被占用便直接跳过，
     * 因此它永远不会阻塞智能体回合。
     */
    private void purgeIfNeeded() {
        long now = System.nanoTime();
        boolean overCapacity = sessions.size() > maxSessions;
        if (!overCapacity && now - lastPurgeNanos < purgeIntervalNanos) {
            return;
        }
        if (!purgeLock.tryLock()) {
            return;
        }
        try {
            lastPurgeNanos = now;
            evictExpired(now);
            evictOverCapacity();
        } finally {
            purgeLock.unlock();
        }
    }

    private void evictExpired(long now) {
        if (ttlNanos <= 0) {
            return;
        }
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, Long> entry : lastAccessNanos.entrySet()) {
            if (now - entry.getValue() > ttlNanos) {
                expired.add(entry.getKey());
            }
        }
        if (!expired.isEmpty()) {
            log.info("Expiring {} idle session(s) after TTL", expired.size());
            expired.forEach(this::evict);
        }
    }

    private void evictOverCapacity() {
        int excess = sessions.size() - maxSessions;
        if (excess <= 0) {
            return;
        }
        List<String> oldestFirst = lastAccessNanos.entrySet().stream()
                .filter(entry -> sessions.containsKey(entry.getKey()))
                .sorted(Comparator.comparingLong(Map.Entry<String, Long>::getValue))
                .map(Map.Entry::getKey)
                .limit(excess)
                .toList();

        if (!oldestFirst.isEmpty()) {
            log.warn("Session capacity {} exceeded; evicting {} least recently used session(s)",
                    maxSessions, oldestFirst.size());
            oldestFirst.forEach(this::evict);
        }
    }

    private void evict(String sessionId) {
        Memory removed = sessions.remove(sessionId);
        lastAccessNanos.remove(sessionId);
        sessionLocks.remove(sessionId);
        if (removed != null) {
            closeQuietly(sessionId, removed);
        }
    }

    /**
     * 释放存储所持有的资源——对于连接池型存储，这只是归还对共享池的引用，
     * 仅当使用它的最后一个会话消失时才真正关闭。
     */
    private void closeQuietly(String sessionId, Memory memory) {
        try {
            memory.close();
        } catch (Exception e) {
            log.warn("Failed to close memory for session '{}': {}", sessionId, e.getMessage(), e);
        }
    }

    /**
     * 活跃会话 id 的快照，用于诊断。
     */
    public List<String> getActiveSessionIds() {
        return List.copyOf(sessions.keySet());
    }

    /**
     * 为向后兼容保留的便捷工厂方法：返回一个内存型会话存储。
     */
    public static SessionManager inMemory() {
        return new SessionManager(MemoryFactory.inMemory());
    }

    /**
     * 为向后兼容保留的便捷工厂方法。
     */
    public static SessionManager inMemory(long compressionTokenThreshold) {
        return new SessionManager(MemoryFactory.inMemory(compressionTokenThreshold));
    }
}
