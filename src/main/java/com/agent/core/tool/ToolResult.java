package com.agent.core.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具调用的结果。
 *
 * <p>直接返回裸 {@code String} 会迫使调用方去猜测工具是否失败——通常只能靠对
 * {@code "Error: ..."} 这样的文本做模式匹配，这种做法很脆弱，并且会悄悄掩盖两类错误的区别：
 * 一类是「模型给了我错误的参数」（值得重试，模型可以自行修正），另一类是「此工具注定不可能成功」
 * （重试纯属浪费）。
 *
 * <p>{@code ToolResult} 将这种区别显式表达出来：
 * <ul>
 *   <li>{@link #success(String)} — 工具成功完成了任务。</li>
 *   <li>{@link #failure(String)} — 永久失败（未知工具、权限被拒、输入永远不可能合法）。重试无济于事。</li>
 *   <li>{@link #retryable(String)} — 瞬时失败（超时、下游返回 503）。智能体可以合理地再次尝试，
 *       例如使用修正后的参数。</li>
 * </ul>
 *
 * <p>无论是否为错误，{@code text} 都会回传给 LLM：模型需要看到错误信息才能自行修正。
 *
 * <p>本记录是不可变且线程安全的。
 *
 * @param text      回传给模型的有效内容（永不为 null，可为空字符串）
 * @param error     调用失败时为真
 * @param retryable 重试有可能成功时为真（仅在 {@code error} 时才有意义）
 * @param metadata  供观察者及指标使用的可选结构化详情（永不为 null）
 */
public record ToolResult(
        String text,
        boolean error,
        boolean retryable,
        Map<String, Object> metadata
) {

    public ToolResult {
        text = text != null ? text : "";
        metadata = metadata == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));
    }

    /** 携带 {@code text} 回传给模型的成功结果。 */
    public static ToolResult success(String text) {
        return new ToolResult(text, false, false, null);
    }

    /** 携带供观察者使用的结构化元数据的成功结果。 */
    public static ToolResult success(String text, Map<String, Object> metadata) {
        return new ToolResult(text, false, false, metadata);
    }

    /** 永久失败。模型会看到 {@code message}，但不期望重试能起作用。 */
    public static ToolResult failure(String message) {
        return new ToolResult(message, true, false, null);
    }

    /** 瞬时失败；智能体可以重试，例如使用修正后的参数。 */
    public static ToolResult retryable(String message) {
        return new ToolResult(message, true, true, null);
    }

    /** 携带显式重试提示与结构化元数据的失败。 */
    public static ToolResult failure(String message, boolean retryable, Map<String, Object> metadata) {
        return new ToolResult(message, true, retryable, metadata);
    }

    /** 便捷方法：{@code !error()}。 */
    public boolean succeeded() {
        return !error;
    }
}
