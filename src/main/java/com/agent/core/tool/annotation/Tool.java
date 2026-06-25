package com.agent.core.tool.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as a tool that can be invoked by the agent.
 * Similar to Spring AI's @Tool annotation.
 *
 * <p>Example usage:</p>
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
     * The name of the tool. If not specified, the method name is used.
     */
    String name() default "";

    /**
     * A human-readable description of what the tool does.
     */
    String description() default "";
}
