package com.agent.test;

import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

/**
 * 天气查询的自定义工具示例类。
 */
class WeatherTools {

    @Tool(name = "get_weather", description = "Get current weather for a city")
    public String getWeather(
            @ToolParam(name = "city", description = "City name") String city
    ) {
        // 模拟实现——实际场景中应调用天气 API
        return String.format("Weather in %s: 22°C, Sunny, Humidity: 45%%", city);
    }

    @Tool(name = "get_forecast", description = "Get weather forecast for a city")
    public String getForecast(
            @ToolParam(name = "city", description = "City name") String city,
            @ToolParam(name = "days", description = "Number of days to forecast", required = false) Integer days
    ) {
        int forecastDays = days != null ? days : 3;
        return String.format("Forecast for %s: Next %d days will be sunny with temperatures 20-25°C", city, forecastDays);
    }
}
