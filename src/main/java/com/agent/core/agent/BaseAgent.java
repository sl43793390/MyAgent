package com.agent.core.agent;

import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.InMemoryStore;
import com.agent.core.memory.Memory;
import com.agent.core.memory.MemoryFactory;
import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.observer.AgentObserver;
import com.agent.core.session.SessionManager;
import com.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Base class for all agent implementations.
 *
 * Thread-safety: Agents are thread-safe when using session-based execution.
 * Each session maintains its own conversation context, allowing multiple users
 * to interact with the same agent instance concurrently.
 *
 * Usage modes:
 * 1. Stateless mode: {@code run(input)} - creates new context each time
 * 2. Session mode: {@code run(input, sessionId)} - reuses context for multi-turn conversations
 */
public abstract class BaseAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final LLMClient llmClient;
    protected final ToolRegistry toolRegistry;
    protected final String systemPrompt;
    protected final LLMParams llmParams;
    protected final SessionManager sessionManager;
    protected AgentObserver observer;

    // Memory factory: controls which Memory implementation is used for new contexts and sessions.
    // Default uses InMemoryStore. Use setMemoryFactory() to switch to Redis, MySQL, etc.
    protected MemoryFactory memoryFactory = sessionId -> new InMemoryStore();

    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this(llmClient, toolRegistry, systemPrompt, LLMParams.DEFAULT);
    }

    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt, LLMParams llmParams) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.systemPrompt = systemPrompt;
        this.llmParams = llmParams != null ? llmParams : LLMParams.DEFAULT;
        this.sessionManager = new SessionManager(memoryFactory);
    }

    /**
     * Set the observer for monitoring agent execution.
     *
     * @param observer the observer, or null to disable
     */
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    /**
     * Get the current observer.
     *
     * @return the current observer, or null if none set
     */
    public AgentObserver getObserver() {
        return observer;
    }

    /**
     * Set the memory factory to control which Memory implementation is used.
     * This affects both stateless mode ({@link #createContext()}) and session mode
     * ({@link #getSessionContext}). Existing sessions are not affected; only new
     * contexts/sessions will use the new factory.
     *
     * <p>Usage:
     * <pre>{@code
     * // Switch to Redis
     * agent.setMemoryFactory(sessionId ->
     *     new RedisMemory("localhost", 6379, "session:" + sessionId, 3600));
     *
     * // Switch to MySQL
     * agent.setMemoryFactory(sessionId ->
     *     new MySQLMemory(jdbcUrl, user, pass, "messages", sessionId));
     * }</pre>
     *
     * @param memoryFactory the factory to create Memory instances, or null to reset to default
     */
    public void setMemoryFactory(MemoryFactory memoryFactory) {
        this.memoryFactory = memoryFactory != null ? memoryFactory : sessionId -> new InMemoryStore();
        this.sessionManager.setMemoryFactory(this.memoryFactory);
    }

    /**
     * Run the agent with the given user input (stateless mode).
     * Creates a new execution context for each call.
     *
     * @param userInput the user's input message
     * @return the agent's result
     */
    public abstract AgentResult run(String userInput);

    /**
     * Run the agent with the given user input and session ID (session mode).
     * Reuses the conversation context for multi-turn dialogues.
     *
     * @param userInput the user's input message
     * @param sessionId unique identifier for the conversation session
     * @return the agent's result
     */
    public abstract AgentResult run(String userInput, String sessionId);

    /**
     * Create a fresh execution context (local memory) for a single run invocation.
     *
     * @return a new Memory instance initialized with the system prompt
     */
    protected Memory createContext() {
        Memory context = memoryFactory.create(null);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            context.add(Message.system(systemPrompt));
        }
        // Auto-configure compression LLM client
        context.setCompressionLLMClient(llmClient);
        return context;
    }

    /**
     * Get or create a session context for multi-turn conversations.
     *
     * @param sessionId unique identifier for the session
     * @return the Memory context for this session
     */
    protected Memory getSessionContext(String sessionId) {
        Memory context = sessionManager.getOrCreate(sessionId, systemPrompt);
        // Auto-configure compression LLM client
        context.setCompressionLLMClient(llmClient);
        return context;
    }

    /**
     * Clear a specific session.
     *
     * @param sessionId the session ID to clear
     */
    public void clearSession(String sessionId) {
        sessionManager.clear(sessionId);
    }

    /**
     * Clear all sessions.
     */
    public void clearAllSessions() {
        sessionManager.clearAll();
    }

    /**
     * Get the number of active sessions.
     *
     * @return number of active sessions
     */
    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }

    /**
     * Manually compress the conversation history for a specific session.
     * This can be called to reduce token usage when the conversation gets too long.
     *
     * @param sessionId the session ID to compress
     * @return true if compression was successful, false otherwise
     */
    public boolean compressSession(String sessionId) {
        Memory context = sessionManager.get(sessionId);
        if (context != null) {
            context.setCompressionLLMClient(llmClient);
            return context.compress();
        }
        return false;
    }

    /**
     * Get the estimated token count for a specific session.
     *
     * @param sessionId the session ID
     * @return estimated token count, or 0 if session doesn't exist
     */
    public long getSessionTokenCount(String sessionId) {
        Memory context = sessionManager.get(sessionId);
        if (context != null) {
            return context.estimateTokens();
        }
        return 0;
    }

    /**
     * Call the LLM with the given memory context and available tools.
     */
    protected LLMResponse callLLM(Memory context) {
        List<Message> messages = context.getMessages();
        return llmClient.chat(messages, toolRegistry.getDefinitions(), llmParams);
    }

    /**
     * Call the LLM with the given memory context, tools, and custom parameters.
     */
    protected LLMResponse callLLM(Memory context, LLMParams params) {
        List<Message> messages = context.getMessages();
        return llmClient.chat(messages, toolRegistry.getDefinitions(), params);
    }
}
