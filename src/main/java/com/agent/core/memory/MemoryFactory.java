package com.agent.core.memory;

/**
 * 为会话创建 {@link Memory}。
 *
 * <p>被 {@link com.agent.core.agent.BaseAgent} 和 {@link com.agent.core.session.SessionManager}
 * 使用，以将内存的创建与具体实现解耦，这样应用就可以在
 * {@link InMemoryStore}、{@link RedisMemory}、{@link MySQLMemory} 或任何自定义存储之间切换，
 * 而无需改动智能体代码。
 *
 * <p>这是一个 {@link FunctionalInterface}，因此可以写成 lambda 或方法引用。
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // In-memory (the default)
 * agent.setMemoryFactory(sessionId -> new InMemoryStore());
 *
 * // Redis — one shared pool, one key per session
 * agent.setMemoryFactory(sessionId -> new RedisMemory("localhost", 6379, "session:" + sessionId, 3600));
 *
 * // MySQL — one shared pool, rows scoped by session id
 * agent.setMemoryFactory(sessionId -> new MySQLMemory(jdbcUrl, user, pass, "messages", sessionId));
 * }</pre>
 *
 * <p>工厂在每个会话首次使用时被调用一次。返回的实例归
 * {@code SessionManager} 所有：当会话过期、被驱逐或管理器关闭时，
 * 管理器会对其调用 {@link Memory#close()}，从而释放该存储持有的任何共享池引用。
 * 由外部提供的池（你拥有的 {@code HikariDataSource} 或
 * {@code JedisPool}）创建的存储不受影响。
 */
@FunctionalInterface
public interface MemoryFactory {

    /**
     * 为给定会话创建一个新的 Memory 实例。
     *
     * @param sessionId 会话 ID（无状态 / 单次运行模式下可为 {@code null}）
     * @return 一个新的 Memory 实例
     */
    Memory create(String sessionId);

    /**
     * 一个生成 {@link InMemoryStore} 实例的工厂，使用默认压缩阈值。
     */
    static MemoryFactory inMemory() {
        return sessionId -> new InMemoryStore();
    }

    /**
     * 一个生成 {@link InMemoryStore} 实例的工厂，使用自定义压缩阈值。
     */
    static MemoryFactory inMemory(long compressionTokenThreshold) {
        return sessionId -> new InMemoryStore(compressionTokenThreshold);
    }
}
