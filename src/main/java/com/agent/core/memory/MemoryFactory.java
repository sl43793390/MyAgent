package com.agent.core.memory;

/**
 * Factory for creating {@link Memory} instances.
 *
 * <p>Used by {@link com.agent.core.agent.BaseAgent} and {@link com.agent.core.session.SessionManager}
 * to decouple memory creation from a specific implementation. This allows switching between
 * {@link InMemoryStore}, {@link RedisMemory}, {@link MySQLMemory} or any custom implementation
 * without changing agent code.
 *
 * <p>This is a {@link FunctionalInterface} so it can be used as a lambda or method reference.
 *
 * <h3>Usage examples</h3>
 * <pre>{@code
 * // In-memory (default)
 * agent.setMemoryFactory(sessionId -> new InMemoryStore());
 *
 * // Redis
 * agent.setMemoryFactory(sessionId -> new RedisMemory("localhost", 6379, "session:" + sessionId, 3600));
 *
 * // MySQL
 * agent.setMemoryFactory(sessionId -> new MySQLMemory(jdbcUrl, user, pass, "messages", sessionId));
 * }</pre>
 */
@FunctionalInterface
public interface MemoryFactory {

    /**
     * Create a new Memory instance for the given session.
     *
     * @param sessionId the session ID (may be {@code null} for stateless / single-run mode)
     * @return a new Memory instance
     */
    Memory create(String sessionId);
}
