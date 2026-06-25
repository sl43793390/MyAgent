package com.agent.core.tool.annotation;

import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Scans objects for @Tool-annotated methods and registers them into a ToolRegistry.
 *
 * <p>Usage:</p>
 * <pre>
 * {@code
 * ToolRegistry registry = new ToolRegistry();
 * AnnotationToolProcessor processor = new AnnotationToolProcessor(registry);
 * processor.register(myToolBean);
 * }
 * </pre>
 */
public class AnnotationToolProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnnotationToolProcessor.class);

    private final ToolRegistry registry;

    public AnnotationToolProcessor(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * Scan the given object for @Tool-annotated methods and register them.
     *
     * @param bean the object to scan
     */
    public void register(Object bean) {
        Class<?> clazz = bean.getClass();

        for (Method method : clazz.getDeclaredMethods()) {
            Tool toolAnnotation = method.getAnnotation(Tool.class);
            if (toolAnnotation == null) {
                continue;
            }

            method.setAccessible(true);

            String toolName = toolAnnotation.name().isEmpty() ? method.getName() : toolAnnotation.name();
            String toolDescription = toolAnnotation.description();

            ToolDefinition definition = buildDefinition(toolName, toolDescription, method);
            com.agent.core.tool.Tool tool = new AnnotatedMethodTool(bean, method, definition);

            registry.register(tool);
            log.info("Registered annotated tool: {}", toolName);
        }
    }

    /**
     * Scan multiple objects.
     */
    public void registerAll(Object... beans) {
        for (Object bean : beans) {
            register(bean);
        }
    }

    private ToolDefinition buildDefinition(String name, String description, Method method) {
        Parameter[] parameters = method.getParameters();

        if (parameters.length == 0) {
            return ToolDefinition.noArgs(name, description);
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        for (Parameter param : parameters) {
            ToolParam paramAnnotation = param.getAnnotation(ToolParam.class);

            String paramName;
            String paramDescription = "";
            boolean isRequired = true;

            if (paramAnnotation != null) {
                paramName = paramAnnotation.name().isEmpty() ? param.getName() : paramAnnotation.name();
                paramDescription = paramAnnotation.description();
                isRequired = paramAnnotation.required();
            } else {
                paramName = param.getName();
            }

            Map<String, Object> prop = new LinkedHashMap<>();
            prop.put("type", mapJavaTypeToJsonType(param.getType()));
            if (!paramDescription.isEmpty()) {
                prop.put("description", paramDescription);
            }
            properties.put(paramName, prop);

            if (isRequired) {
                required.add(paramName);
            }
        }

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }

        return new ToolDefinition(name, description, schema);
    }

    private String mapJavaTypeToJsonType(Class<?> type) {
        if (type == String.class || type == CharSequence.class) {
            return "string";
        } else if (type == int.class || type == Integer.class
                || type == long.class || type == Long.class) {
            return "integer";
        } else if (type == double.class || type == Double.class
                || type == float.class || type == Float.class) {
            return "number";
        } else if (type == boolean.class || type == Boolean.class) {
            return "boolean";
        } else {
            return "string";
        }
    }
}
