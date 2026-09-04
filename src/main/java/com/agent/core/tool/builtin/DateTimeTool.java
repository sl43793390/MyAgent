package com.agent.core.tool.builtin;

import com.agent.core.tool.RiskLevel;
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 返回当前日期和时间。
 *
 * <p>功能简单却很有必要：没有时钟的模型在被问到「离周五还有多久」时，要么拒绝回答，要么凭空编造一个日期。
 */
public class DateTimeTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);

    private final ZoneId zone;

    public DateTimeTool() {
        this(ZoneId.systemDefault());
    }

    /**
     * @param zone 时间戳所使用的时区
     */
    public DateTimeTool(ZoneId zone) {
        this.zone = zone != null ? zone : ZoneId.systemDefault();
    }

    @Override
    public ToolDefinition getDefinition() {
        return ToolDefinition.noArgs(
                "get_datetime",
                "Get the current date and time in ISO 8601 format, including the time zone offset.");
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String dateTime = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        log.debug("Current datetime: {}", dateTime);
        return ToolResult.success(dateTime);
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SAFE;
    }
}
