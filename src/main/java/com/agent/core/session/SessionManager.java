package com.agent.core.session;

import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.Memory;
import com.agent.core.memory.MemoryFactory;
import com.agent.core.model.Message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session manager for maintaining conversation context across multiple agent runs.
 *
 * Thread-safe: Uses ConcurrentHashMap for session storage.
 *
 * Usage:
 * <pre>
 * {@code
 * SessionManager sessionManager = new SessionManager();
 *
 * // First message in a new session
 * Memory context = sessionManager.getOrCreate("user-123", systemPrompt);
 * context.add(Message.user("Hello"));
 * // ... run agent with context
 *
 * // Follow-up message - will remember previous conversation
 * Memory context = sessionManager.getOrCreate("user-123", systemPrompt);
 * context.add(Message.user("What did I just ask?"));
 * // ... run agent with context
 *
 * // Clear session when done
 * sessionManager.clear("user-123");
 * }
 * </pre>
 */
public class SessionManager {

    private final Map<String, Memory> sessions = new ConcurrentHashMap<>();
    private MemoryFactory memoryFactory;

    public SessionManager() {
        this(Memory.DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Create a session manager with a custom compression token threshold.
     *
     * @param compressionTokenThreshold token threshold to trigger auto-compression
     */
    public SessionManager(long compressionTokenThreshold) {
        this.memoryFactory = sessionId ->
                new InMemoryStore(compressionTokenThreshold);
    }

    /**
     * Create a session manager with a custom {@link MemoryFactory}.
     * Use this constructor to switch to Redis, MySQL, or any other Memory implementation.
     *
     * @param memoryFactory the factory that creates Memory instances per session
     */
    public SessionManager(MemoryFactory memoryFactory) {
        this.memoryFactory = memoryFactory;
    }

    /**
     * Update the {@link MemoryFactory} used for creating new sessions.
     * Existing sessions are not affected; only new sessions will use the new factory.
     *
     * @param memoryFactory the new factory, or null to reset to default InMemoryStore
     */
    public void setMemoryFactory(MemoryFactory memoryFactory) {
        this.memoryFactory = memoryFactory != null ? memoryFactory
                : sessionId -> new InMemoryStore(Memory.DEFAULT_COMPRESSION_TOKEN_THRESHOLD);
    }

    /**
     * Get or create a session context for the given session ID.
     * If the session doesn't exist, creates a new one with the system prompt.
     *
     * @param sessionId   unique identifier for the session (e.g., user ID, conversation ID)
     * @param systemPrompt system prompt to initialize the session with (only used for new sessions)
     * @return the Memory context for this session
     */
    public Memory getOrCreate(String sessionId, String systemPrompt) {
        return sessions.computeIfAbsent(sessionId, id -> {
            Memory memory = memoryFactory.create(id);
            if (systemPrompt != null && !systemPrompt.isBlank()) {
                memory.add(Message.system(systemPrompt));
            }
            return memory;
        });
    }

    /**
     * Get an existing session context.
     *
     * @param sessionId the session ID
     * @return the Memory context, or null if session doesn't exist
     */
    public Memory get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Check if a session exists.
     *
     * @param sessionId the session ID
     * @return true if the session exists
     */
    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * Clear a specific session.
     *
     * @param sessionId the session ID to clear
     */
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    /**
     * Clear all sessions.
     */
    public void clearAll() {
        sessions.clear();
    }

    /**
     * Get the number of active sessions.
     *
     * @return number of active sessions
     */
    public int getActiveSessionCount() {
        return sessions.size();
    }
}
