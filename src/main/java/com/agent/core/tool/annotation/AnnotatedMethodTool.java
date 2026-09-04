package com.agent.core.tool.annotation;

import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * 包装一个带 @Tool 注解方法的 Tool 实现。
 */
class AnnotatedMethodTool implements com.agent.core.tool.Tool {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedMethodTool.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Object target;
    private final Method method;
    private final ToolDefinition definition;

    AnnotatedMethodTool(Object target, Method method, ToolDefinition definition) {
        this.target = target;
        this.method = method;
        this.definition = definition;
    }

    @Override
    public ToolDefinition getDefinition() {
        return definition;
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        try {
            Parameter[] params = method.getParameters();
            Object[] args = new Object[params.length];

            for (int i = 0; i < params.length; i++) {
                ToolParam paramAnnotation = params[i].getAnnotation(ToolParam.class);
                String paramName;

                if (paramAnnotation != null) {
                    paramName = paramAnnotation.name().isEmpty() ? params[i].getName() : paramAnnotation.name();
                } else {
                    paramName = params[i].getName();
                }

                Object value = arguments.get(paramName);
                args[i] = convertValue(value, params[i].getType());
            }

            Object result = method.invoke(target, args);
            String text = result != null ? result.toString() : "";
            return ToolResult.success(text);

        } catch (InvocationTargetException e) {
            // 被注解的方法本身抛出了异常。这里抛出其原始 cause，而不是反射包装层。
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            log.error("Tool method '{}' failed: {}", method.getName(), cause.toString(), cause);
            return ToolResult.failure("Error executing tool: " + cause.getMessage());
        } catch (Exception e) {
            // 反射初始化 / 参数转换失败。
            log.error("Failed to execute annotated tool method '{}': {}", method.getName(), e.getMessage(), e);
            return ToolResult.failure("Error executing tool: " + e.getMessage());
        }
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return getDefaultValue(targetType);
        }

        if (targetType.isInstance(value)) {
            return value;
        }

        // 转换常见类型。这些值来自 Jackson 解析的 JSON，因此数字可能是 Integer/Double/Long，
        // 也可能（取决于参数的解析方式）是字符串；两种情况都要处理，而不能假定某一种具体的
        // 运行时类型（否则 String 上的 ClassCastException 会表现为一个令人困惑的工具失败）。
        if (targetType == String.class) {
            return value.toString();
        } else if (targetType == int.class || targetType == Integer.class) {
            return toNumber(value).intValue();
        } else if (targetType == long.class || targetType == Long.class) {
            return toNumber(value).longValue();
        } else if (targetType == double.class || targetType == Double.class) {
            return toNumber(value).doubleValue();
        } else if (targetType == float.class || targetType == Float.class) {
            return toNumber(value).floatValue();
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean flag) {
                return flag;
            }
            return Boolean.valueOf(value.toString().trim());
        }

        return value;
    }

    private static Number toNumber(Object value) {
        if (value instanceof Number number) {
            return number;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            if (text.contains(".") || text.contains("e") || text.contains("E")) {
                return Double.valueOf(text);
            }
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Cannot convert '" + value + "' to a number", e);
        }
    }

    private Object getDefaultValue(Class<?> type) {
        if (type.isPrimitive()) {
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == double.class) return 0.0;
            if (type == float.class) return 0.0f;
            if (type == boolean.class) return false;
        }
        return null;
    }
}
