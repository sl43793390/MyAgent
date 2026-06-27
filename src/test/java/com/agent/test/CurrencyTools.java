package com.agent.test;

import com.agent.core.tool.annotation.Tool;
import com.agent.core.tool.annotation.ToolParam;

class CurrencyTools {

    @Tool(name = "convert_currency", description = "Convert amount from one currency to another")
    public String convertCurrency(
            @ToolParam(name = "amount", description = "Amount to convert") Double amount,
            @ToolParam(name = "from", description = "Source currency code (e.g., USD, EUR, JPY)") String from,
            @ToolParam(name = "to", description = "Target currency code") String to
    ) {
        // Mock exchange rates
        double rate = getMockRate(from, to);
        double converted = amount * rate;
        return String.format("%.2f %s = %.2f %s (rate: %.4f)", amount, from, converted, to, rate);
    }

    private double getMockRate(String from, String to) {
        // Mock rates for demo
        if (from.equals("USD") && to.equals("CNY")) return 7.2;
        if (from.equals("USD") && to.equals("EUR")) return 0.85;
        if (from.equals("USD") && to.equals("JPY")) return 110.0;
        if (from.equals("EUR") && to.equals("USD")) return 1.18;
        if (from.equals("JPY") && to.equals("USD")) return 0.0091;
        return 1.0;
    }
}