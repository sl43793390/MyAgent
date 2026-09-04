package com.agent.core.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 将一个方法标记为可供智能体调用的工具。
 * 类似于 Spring AI 的 @Tool 注解。
 *
 * <p>用法示例：</p>
 * <pre>
 * {@code
 * @Tool(name = "calculator", description = "Evaluate a mathematical expression")
 * public String calculate(@ToolParam(name = "expression", description = "The math expression") String expression) {
 *     // ...
 * }
 * }
 * </pre>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Tool {

    /**
     * 工具的名称。若未指定，则使用方法名。
     */
    String name() default "";

    /**
     * 对工具功能的可读描述。
     */
    String description() default "";
}
