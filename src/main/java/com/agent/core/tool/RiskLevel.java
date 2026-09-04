package com.agent.core.tool;

/**
 * 当模型被诱导而误用某个工具时，该工具可能造成的危害程度。
 *
 * <p>工具通过 {@link Tool#riskLevel()} 声明自身的风险等级，使应用能够评估——
 * 并对"究竟向一个由不可信输入驱动的模型开放了哪些能力"进行把关。
 */
public enum RiskLevel {

    /**
     * 只读操作或纯计算。最坏情况不过是模型浪费一次调用。
     * 示例：{@code get_datetime}、{@code calculator}、{@code file_read}。
     */
    SAFE,

    /**
     * 可以修改状态或访问网络，但只能通过工具自身受限的接口
     * （在本框架中，还需处于沙箱内）。
     * 示例：{@code file_write}、{@code file_edit}、{@code web_fetch}。
     */
    SENSITIVE,

    /**
     * 可以执行任意代码，或以其他方式突破工具预期的边界。
     * 示例：{@code command_execute}。
     *
     * <p>{@link ToolRegistry} 会拒绝注册 {@code DANGEROUS} 工具，除非应用显式选择允许。
     */
    DANGEROUS
}
