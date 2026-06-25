package com.agent.core.tool.annotation;

import com.agent.core.tool.ToolDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * A Tool implementation that wraps a @Tool-annotated method.
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
    public String execute(Map<String, Object> arguments) {
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
            return result != null ? result.toString() : "";

        } catch (Exception e) {
            log.error("Failed to execute annotated tool method '{}': {}", method.getName(), e.getMessage(), e);
            return "Error executing tool: " + e.getMessage();
        }
    }

    private Object convertValue(Object value, Class<?> targetType) {
        if (value == null) {
            return getDefaultValue(targetType);
        }

        if (targetType.isInstance(value)) {
            return value;
        }

        // Convert common types
        if (targetType == String.class) {
            return value.toString();
        } else if (targetType == int.class || targetType == Integer.class) {
            return ((Number) value).intValue();
        } else if (targetType == long.class || targetType == Long.class) {
            return ((Number) value).longValue();
        } else if (targetType == double.class || targetType == Double.class) {
            return ((Number) value).doubleValue();
        } else if (targetType == float.class || targetType == Float.class) {
            return ((Number) value).floatValue();
        } else if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(value.toString());
        }

        return value;
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
