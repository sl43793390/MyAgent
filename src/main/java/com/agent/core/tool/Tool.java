package com.agent.core.tool;

import java.time.Duration;
import java.util.Map;

/**
 * 智能体可向 LLM 暴露的一项能力。
 *
 * <p>实现必须保证并发调用安全：框架并不会对工具执行做串行化，
 * 而单个 LLM 响应可能请求多个工具调用，调用方随后可能会并行运行它们。
 *
 * <p>实现不得抛出异常。任何失败——错误的参数、I/O 错误、超时、
 * 权限问题——都应作为 {@link ToolResult} 返回，以便模型能看到问题所在
 * 并自行修正。从 {@link #execute(Map)} 逃逸出的异常会被 {@link ToolRegistry}
 * 当作可重试的失败处理，但这样会丢失一个结构良好的 {@link ToolResult} 所包含的细微差别。
 */
public interface Tool {

    /** 展示给 LLM 的模式与描述。 */
    ToolDefinition getDefinition();

    /**
     * 运行该工具。
     *
     * @param arguments 从模型的 JSON 解码得到的参数，永不为 null
     * @return 结果；永不为 null
     */
    ToolResult execute(Map<String, Object> arguments);

    /**
     * 单次调用在 {@link ToolRegistry} 放弃它之前可以运行多久。
     * 默认值：60 秒。返回 {@link Duration#ZERO} 或负时长可对该特定工具禁用
     * 超时。
     */
    default Duration timeout() {
        return Duration.ofSeconds(60);
    }

    /**
     * 该工具被滥用时可能造成的危害。默认值：{@link RiskLevel#SAFE}。
     * 请如实覆盖——注册表会据此对注册进行把关。
     */
    default RiskLevel riskLevel() {
        return RiskLevel.SAFE;
    }
}
