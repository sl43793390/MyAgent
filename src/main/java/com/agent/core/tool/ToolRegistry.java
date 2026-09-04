package com.agent.core.tool;

import com.agent.core.observer.AgentObserver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 智能体向 LLM 暴露的工具的注册表。
 *
 * <p>除名称查找外，它还负责三项横切关注点：
 * <ol>
 *   <li><b>注册把关。</b> 声明 {@link RiskLevel#DANGEROUS} 的工具会被拒绝，除非
 *       应用通过 {@link #setAllowDangerousTools(boolean)} 显式开启。这让
 *       “我的智能体可以运行 shell 命令”在调用点就显式声明，而不是一个静默的默认值。</li>
 *   <li><b>超时强制。</b> 每次调用都受 {@link Tool#timeout()} 约束，因此一个
 *       挂起的工具无法永远拖住智能体运行。注意工作线程是被中断而非杀死：忽略中断的工具
 *       会在后台继续运行。</li>
 *   <li><b>可观测性。</b> 每次调用都会触发开始/结束/错误的回调。</li>
 * </ol>
 *
 * <p>本类是线程安全的。
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 仅用于强制各工具超时的有界线程池。线程是守护线程，因此永远不会
     * 阻止 JVM 退出；该线程池在多个注册表之间共享，因为它不持有任何状态。
     */
    private static final ThreadPoolExecutor TIMEOUT_RUNNER = new ThreadPoolExecutor(
            0, 32, 60L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            daemonThreads(),
            new ThreadPoolExecutor.AbortPolicy());

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private volatile AgentObserver observer;
    private volatile boolean allowDangerousTools = false;

    /**
     * 设置每次工具执行前后被通知的观察者。
     *
     * @param observer 观察者，传入 null 可禁用
     */
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    /**
     * 获取当前的观察者。
     *
     * @return 当前的观察者，若未设置则返回 null
     */
    public AgentObserver getObserver() {
        return observer;
    }

    /**
     * 是否允许注册 {@link RiskLevel#DANGEROUS} 工具。默认值：{@code false}。
     *
     * <p>仅当智能体的输入可信、且进程本身已在沙箱中时才启用——危险的工具会把任何提示注入
     * 变成代码执行。
     */
    public void setAllowDangerousTools(boolean allowDangerousTools) {
        this.allowDangerousTools = allowDangerousTools;
        log.warn("Dangerous tool registration {}", allowDangerousTools ? "ENABLED" : "disabled");
    }

    /**
     * 是否允许注册危险工具。
     */
    public boolean isAllowDangerousTools() {
        return allowDangerousTools;
    }

    /**
     * 注册一个工具。
     *
     * @throws SecurityException 若工具为 {@link RiskLevel#DANGEROUS} 且危险工具
     *                          未获显式允许
     */
    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool must not be null");
        ToolDefinition definition = Objects.requireNonNull(tool.getDefinition(),
                "tool definition must not be null");
        String name = definition.name();

        if (tool.riskLevel() == RiskLevel.DANGEROUS && !allowDangerousTools) {
            throw new SecurityException("Refusing to register DANGEROUS tool '" + name + "': it can "
                    + "execute arbitrary code driven by untrusted model output. Call "
                    + "ToolRegistry#setAllowDangerousTools(true) only if you accept that risk.");
        }

        Tool previous = tools.put(name, tool);
        if (previous != null) {
            log.warn("Tool '{}' is already registered, overwriting", name);
        } else {
            log.debug("Registered tool: {}", name);
        }
    }

    /**
     * 一次性注册多个工具。
     *
     * @throws SecurityException 若任一工具为 {@link RiskLevel#DANGEROUS} 且危险工具
     *                          未获显式允许
     */
    public void registerAll(Tool... toolList) {
        if (toolList == null) {
            return;
        }
        for (Tool tool : toolList) {
            if (tool != null) {
                register(tool);
            }
        }
    }

    /**
     * 按名称注销一个工具。
     */
    public void unregister(String name) {
        tools.remove(name);
        log.debug("Unregistered tool: {}", name);
    }

    /**
     * 按名称获取一个工具。
     *
     * @return 该工具，若未注册则返回 null
     */
    public Tool get(String name) {
        return name == null ? null : tools.get(name);
    }

    /**
     * 检查某个工具是否已注册。
     */
    public boolean has(String name) {
        return name != null && tools.containsKey(name);
    }

    /**
     * 所有已注册工具的定义，按名称排序。
     *
     * <p>排序保证了发送给 LLM 的请求在各次调用之间字节稳定，这对于
     * 以请求体精确内容作为提示缓存键的供应商很重要。
     *
     * @return 一个不可变、已排序的列表
     */
    public List<ToolDefinition> getDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .sorted(Comparator.comparing(ToolDefinition::name))
                .toList();
    }

    /**
     * 所有已注册工具的名称，按字母顺序排序。
     */
    public List<String> getToolNames() {
        return tools.keySet().stream().sorted().toList();
    }

    /**
     * 以 JSON 参数执行一个工具。
     *
     * <p>本方法永远不会抛出：所有失败情况都会作为 {@link ToolResult} 上报，以便模型看到
     * 错误并自行修正。
     *
     * @param name      工具名称
     * @param arguments 参数的 JSON 对象；null 或空字符串表示「无参数」
     * @return 工具结果，永不为 null
     */
    public ToolResult execute(String name, String arguments) {
        long startNanos = System.nanoTime();
        AgentObserver obs = observer;
        Tool tool = name == null ? null : tools.get(name);

        if (obs != null) {
            obs.onToolCallStart(name, arguments);
        }

        if (tool == null) {
            String message = "Unknown tool '" + name + "'. Available tools: " + getToolNames();
            log.warn(message);
            notifyError(obs, name, new IllegalArgumentException(message), startNanos);
            return ToolResult.failure(message);
        }

        Map<String, Object> args;
        try {
            args = parseArguments(arguments);
        } catch (Exception e) {
            // 格式错误的 JSON 通常是模型自己的失误——它可以在下一轮自行修正。
            String message = "Invalid arguments for tool '" + name + "': " + e.getMessage();
            log.warn(message);
            notifyError(obs, name, e, startNanos);
            return ToolResult.retryable(message);
        }

        ToolResult result = invokeWithTimeout(tool, args);
        long durationMillis = elapsedMillis(startNanos);

        if (obs != null) {
            if (result.error()) {
                obs.onToolCallError(name, new IllegalStateException(result.text()), durationMillis);
            } else {
                obs.onToolCallEnd(name, result.text(), durationMillis);
            }
        }

        if (result.error()) {
            log.warn("Tool '{}' failed in {}ms (retryable={}): {}",
                    name, durationMillis, result.retryable(), result.text());
        } else {
            log.debug("Tool '{}' completed in {}ms", name, durationMillis);
        }
        return result;
    }

    private ToolResult invokeWithTimeout(Tool tool, Map<String, Object> args) {
        String name = tool.getDefinition().name();
        Duration timeout = tool.timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return safeExecute(tool, args);
        }

        Future<ToolResult> future;
        try {
            future = TIMEOUT_RUNNER.submit(() -> safeExecute(tool, args));
        } catch (RejectedExecutionException e) {
            // 线程池已饱和——降级到在调用者线程上执行，而不是失败。
            log.warn("Tool runner pool saturated; executing '{}' on the calling thread", name);
            return safeExecute(tool, args);
        }

        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return ToolResult.retryable("Tool '" + name + "' timed out after "
                    + timeout.toMillis() + " ms.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return ToolResult.retryable("Tool '" + name + "' was interrupted.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool '{}' threw: {}", name, cause.toString(), cause);
            return ToolResult.retryable("Tool '" + name + "' failed: " + cause);
        }
    }

    /**
     * 工具应当返回失败而非抛出异常，但一个行为不端的工具绝不能
     * 拖垮整个智能体循环。
     */
    private ToolResult safeExecute(Tool tool, Map<String, Object> args) {
        try {
            ToolResult result = tool.execute(args);
            return result != null ? result : ToolResult.success("");
        } catch (Exception e) {
            String name = tool.getDefinition().name();
            log.error("Tool '{}' threw an exception: {}", name, e.toString(), e);
            return ToolResult.retryable("Tool '" + name + "' failed: " + e);
        }
    }

    private static Map<String, Object> parseArguments(String arguments) throws Exception {
        if (arguments == null || arguments.isBlank()) {
            return Map.of();
        }
        return objectMapper.readValue(arguments, new TypeReference<Map<String, Object>>() {});
    }

    private static void notifyError(AgentObserver observer, String name, Exception error, long startNanos) {
        if (observer != null) {
            observer.onToolCallError(name, error, elapsedMillis(startNanos));
        }
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static ThreadFactory daemonThreads() {
        return runnable -> {
            Thread thread = new Thread(runnable, "agent-tool-runner");
            thread.setDaemon(true);
            return thread;
        };
    }
}
