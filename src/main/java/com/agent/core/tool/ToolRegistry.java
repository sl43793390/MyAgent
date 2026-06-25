package com.agent.core.tool;

import com.agent.core.observer.AgentObserver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing available tools.
 */
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private AgentObserver observer;

    /**
     * Set the observer for monitoring tool executions.
     *
     * @param observer the observer, or null to disable
     */
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    /**
     * Get the current observer.
     *
     * @return the current observer, or null if none set
     */
    public AgentObserver getObserver() {
        return observer;
    }

    /**
     * Register a tool.
     */
    public void register(Tool tool) {
        String name = tool.getDefinition().name();
        if (tools.containsKey(name)) {
            log.warn("Tool '{}' is already registered, overwriting", name);
        }
        tools.put(name, tool);
        log.debug("Registered tool: {}", name);
    }

    /**
     * Unregister a tool by name.
     */
    public void unregister(String name) {
        tools.remove(name);
        log.debug("Unregistered tool: {}", name);
    }

    /**
     * Get a tool by name.
     */
    public Tool get(String name) {
        return tools.get(name);
    }

    /**
     * Check if a tool is registered.
     */
    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /**
     * Get all tool definitions.
     */
    public java.util.List<ToolDefinition> getDefinitions() {
        return tools.values().stream()
                .map(Tool::getDefinition)
                .toList();
    }

    /**
     * Execute a tool with JSON arguments.
     *
     * @param name      the tool name
     * @param arguments JSON string of arguments
     * @return the tool execution result
     */
    public String execute(String name, String arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + name);
        }

        long startTime = System.currentTimeMillis();

        // Notify observer before tool execution
        if (observer != null) {
            observer.onToolCallStart(name, arguments);
        }

        try {
            Map<String, Object> args = objectMapper.readValue(
                    arguments,
                    new TypeReference<Map<String, Object>>() {}
            );
            String result = tool.execute(args);

            // Notify observer after tool execution
            if (observer != null) {
                long duration = System.currentTimeMillis() - startTime;
                observer.onToolCallEnd(name, result, duration);
            }

            return result;

        } catch (Exception e) {
            // Notify observer on error
            if (observer != null) {
                long duration = System.currentTimeMillis() - startTime;
                observer.onToolCallError(name, e, duration);
            }

            log.error("Failed to execute tool '{}': {}", name, e.getMessage(), e);
            return "Error executing tool: " + e.getMessage();
        }
    }
}
