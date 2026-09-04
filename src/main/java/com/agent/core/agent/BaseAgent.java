package com.agent.core.agent;

import com.agent.core.llm.LLMClient;
import com.agent.core.llm.LLMParams;
import com.agent.core.memory.AbstractMemory;
import com.agent.core.memory.Memory;
import com.agent.core.memory.MemoryCompressor;
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
 * 所有智能体实现的基础类。
 *
 * <p>线程安全：智能体在基于会话的执行中是线程安全的。每个会话拥有自己的对话上下文，
 * 因此多个用户可以共享同一个智能体实例。但是，单个会话不能被两个轮次同时驱动——子类通过
 * {@link SessionManager#withSessionLock(String, java.util.function.Supplier)} 来串行化该操作。
 *
 * <h3>使用模式</h3>
 * <ol>
 *   <li><b>无状态：</b> {@code run(input)} —— 每次调用都使用全新的上下文。</li>
 *   <li><b>会话：</b> {@code run(input, sessionId)} —— 上下文在多个轮次之间被复用。</li>
 * </ol>
 *
 * <p>智能体会持有资源（其 {@link SessionManager}，以及经由它持有的每个会话的
 * {@link Memory}）。因此它实现了 {@link AutoCloseable}：调用 {@link #close()}，或在
 * try-with-resources 块中使用它，以便池化存储释放其池引用。
 */
public abstract class BaseAgent implements AutoCloseable {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final LLMClient llmClient;
    protected final ToolRegistry toolRegistry;
    protected final String systemPrompt;
    protected final LLMParams llmParams;
    protected final SessionManager sessionManager;
    protected final MemoryCompressor compressor;

    /**
     * 本智能体事件的观察者。volatile（保证可见性）：观察者可能在智能体已于其他线程
     * 开始处理请求之后才被挂载。
     */
    protected volatile AgentObserver observer;

    /**
     * 控制新上下文与会话由哪个 {@link Memory} 支撑的工厂。volatile（保证可见性），因为它可能在
     * 运行时被替换。
     */
    protected volatile MemoryFactory memoryFactory = MemoryFactory.inMemory();

    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt) {
        this(llmClient, toolRegistry, systemPrompt, LLMParams.DEFAULT, null);
    }

    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                        LLMParams llmParams) {
        this(llmClient, toolRegistry, systemPrompt, llmParams, null);
    }

    /**
     * @param sessionManager 外部配置的会话管理器，为 null 时使用默认值
     *                       （2&nbsp;小时 TTL、1,000 个会话、内存存储）
     */
    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, String systemPrompt,
                        LLMParams llmParams, SessionManager sessionManager) {
        if (llmClient == null) {
            throw new IllegalArgumentException("llmClient must not be null");
        }
        if (toolRegistry == null) {
            throw new IllegalArgumentException("toolRegistry must not be null");
        }
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.systemPrompt = systemPrompt;
        this.llmParams = llmParams != null ? llmParams : LLMParams.DEFAULT;
        // 当 SessionManager 由外部提供时，遵循其配置（包括它构建时所使用过的任何
        // MemoryFactory）。只有在未提供时，我们才构建一个绑定到本智能体当前记忆工厂的
        // 默认管理器。
        this.sessionManager = sessionManager != null
                ? sessionManager
                : new SessionManager(this.memoryFactory);
        this.compressor = new MemoryCompressor(llmClient);
    }

    /**
     * 挂载在执行期间被通知的观察者。
     *
     * @param observer 观察者，为 null 表示禁用
     */
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    /**
     * 当前的观察者，未挂载时为 null。
     */
    public AgentObserver getObserver() {
        return observer;
    }

    /**
     * 选择由哪个 {@link Memory} 实现来支撑新的上下文与会话。
     * 已存在的会话不受影响。
     *
     * <pre>{@code
     * // 使用 Redis
     * agent.setMemoryFactory(sessionId ->
     *     new RedisMemory("localhost", 6379, "session:" + sessionId, 3600));
     *
     * // 使用 MySQL —— 同一 URL 上的会话共享一个连接池
     * agent.setMemoryFactory(sessionId ->
     *     new MySQLMemory(jdbcUrl, user, pass, "messages", sessionId));
     * }</pre>
     *
     * @param memoryFactory 工厂，为 null 时重置为内存存储
     */
    public void setMemoryFactory(MemoryFactory memoryFactory) {
        this.memoryFactory = memoryFactory != null ? memoryFactory : MemoryFactory.inMemory();
        this.sessionManager.setMemoryFactory(this.memoryFactory);
    }

    /**
     * 当前用于新上下文与会话的工厂。
     */
    public MemoryFactory getMemoryFactory() {
        return memoryFactory;
    }

    /**
     * 以无状态模式在 {@code userInput} 上运行智能体：每次调用都使用全新上下文。
     */
    public abstract AgentResult run(String userInput);

    /**
     * 在已有或新建的会话中，于 {@code userInput} 上运行智能体。
     *
     * @param userInput 用户的输入消息
     * @param sessionId 对话会话的唯一标识符
     */
    public abstract AgentResult run(String userInput, String sessionId);

    /**
     * 创建一个以系统提示词为种子的全新、单次运行上下文。
     */
    protected Memory createContext() {
        Memory context = memoryFactory.create(null);
        if (context == null) {
            throw new IllegalStateException("MemoryFactory returned null for a stateless context");
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            context.add(Message.system(systemPrompt));
        }
        return attachCompressor(context);
    }

    /**
     * 获取或创建某个会话的上下文。
     */
    protected Memory getSessionContext(String sessionId) {
        return attachCompressor(sessionManager.getOrCreate(sessionId, systemPrompt));
    }

    /**
     * 为某个记忆注入智能体的压缩器，以便对长对话进行摘要。
     *
     * <p>{@link Memory} 契约本身并不依赖大模型层；压缩是一个可选的协作方，
     * 由智能体在将存储绑定到会话时注入。
     */
    protected Memory attachCompressor(Memory memory) {
        if (memory instanceof AbstractMemory abstractMemory && abstractMemory.compressor() == null) {
            abstractMemory.setCompressor(compressor);
        }
        return memory;
    }

    /**
     * 丢弃一个会话并释放其资源。
     */
    public void clearSession(String sessionId) {
        sessionManager.clear(sessionId);
    }

    /**
     * 丢弃所有会话并释放其资源。
     */
    public void clearAllSessions() {
        sessionManager.clearAll();
    }

    /**
     * 活跃会话的数量。
     */
    public int getActiveSessionCount() {
        return sessionManager.getActiveSessionCount();
    }

    /**
     * 按需压缩某个会话的历史记录。
     *
     * @return 当历史被重写时返回 true
     */
    public boolean compressSession(String sessionId) {
        Memory context = sessionManager.get(sessionId);
        if (context != null) {
            attachCompressor(context);
            return context.compress();
        }
        return false;
    }

    /**
     * 某个会话当前持有的估算 Token 数。
     *
     * @return 估算值；若会话不存在则返回 0
     */
    public long getSessionTokenCount(String sessionId) {
        Memory context = sessionManager.get(sessionId);
        return context != null ? context.estimateTokens() : 0L;
    }

    /**
     * 使用上下文的消息与注册表的工具调用大模型。
     *
     * @throws com.agent.core.llm.LLMException （或其子类）当 provider 调用失败时
     */
    protected LLMResponse callLLM(Memory context) {
        return callLLM(context, llmParams);
    }

    /**
     * 使用上下文的消息、注册表的工具以及显式参数调用大模型。
     *
     * @throws com.agent.core.llm.LLMException （或其子类）当 provider 调用失败时
     */
    protected LLMResponse callLLM(Memory context, LLMParams params) {
        List<Message> messages = context.getMessages();
        return llmClient.chat(messages, toolRegistry.getDefinitions(), params);
    }

    /**
     * 释放所有会话及其资源。调用之后不得再次使用该智能体。
     */
    @Override
    public void close() {
        try {
            sessionManager.close();
        } catch (Exception e) {
            log.warn("Failed to close SessionManager: {}", e.getMessage(), e);
        }
    }
}
