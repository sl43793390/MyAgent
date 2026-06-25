package com.agent.core.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Describes a parameter of a @Tool-annotated method.
 *
 * <p>Example usage:</p>
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
     * The name of the parameter. If not specified, the actual parameter name is used
     * (requires -parameters compiler flag).
     */
    String name() default "";

    /**
     * A human-readable description of the parameter.
     */
    String description() default "";

    /**
     * Whether this parameter is required.
     */
    boolean required() default true;
}
