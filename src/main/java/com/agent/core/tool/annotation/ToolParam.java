package com.agent.core.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 描述带 @Tool 注解方法的某个参数。
 *
 * <p>用法示例：</p>
 * <pre>
 * {@code
 * public String search(
 *     @ToolParam(name = "query", description = "The search query") String query,
 *     @ToolParam(name = "limit", description = "Max results", required = false) int limit
 * ) {
 *     // ...
 * }
 * }
 * </pre>
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ToolParam {

    /**
     * 参数的名称。若未指定，则使用实际的参数名
     * （需要 -parameters 编译器参数）。
     */
    String name() default "";

    /**
     * 参数的可读描述。
     */
    String description() default "";

    /**
     * 该参数是否为必填。
     */
    boolean required() default true;
}
