package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Built-in tool for getting the current date and time.
 */
public class DateTimeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.noArgs(
                "get_datetime",
                "Get the current date and time in ISO 8601 format"
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String dateTime = java.time.LocalDateTime.now().toString();
        log.debug("Current datetime: {}", dateTime);
        return dateTime;
    }
}
