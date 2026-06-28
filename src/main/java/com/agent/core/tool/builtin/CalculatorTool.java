package com.agent.core.tool.builtin;

import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Built-in tool for performing basic arithmetic calculations.
 */
public class CalculatorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "expression", Map.of(
                        "type", "string",
                        "description", "The mathematical expression to evaluate (e.g., '2 + 2', '10 * 5')"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"expression"}
        );

        return new ToolDefinition(
                "calculator",
                "Evaluate a mathematical expression. Supports +, -, *, /, and parentheses.",
                parameters
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object exprObj = arguments.get("expression");
        String expression = exprObj != null ? exprObj.toString() : null;
        if (expression == null || expression.isBlank()) {
            return "Error: 'expression' parameter is required";
        }

        try {
            double result = evaluate(expression);
            log.debug("Calculated: {} = {}", expression, result);
            return String.valueOf(result);
        } catch (Exception e) {
            log.error("Failed to evaluate expression '{}': {}", expression, e.getMessage());
            return "Error evaluating expression: " + e.getMessage();
        }
    }

    private double evaluate(String expression) {
        return new Object() {
            int pos = -1;
            int ch;

            double parse() {
                nextChar();
                double x = parseExpression();
                if (pos < expression.length()) {
                    throw new RuntimeException("Unexpected character: " + (char) ch);
                }
                return x;
            }

            void nextChar() {
                ch = (++pos < expression.length()) ? expression.charAt(pos) : -1;
            }

            boolean eat(int charToEat) {
                while (ch == ' ') nextChar();
                if (ch == charToEat) {
                    nextChar();
                    return true;
                }
                return false;
            }

            double parseExpression() {
                double x = parseTerm();
                for (; ; ) {
                    if (eat('+')) x += parseTerm();
                    else if (eat('-')) x -= parseTerm();
                    else return x;
                }
            }

            double parseTerm() {
                double x = parseFactor();
                for (; ; ) {
                    if (eat('*')) x *= parseFactor();
                    else if (eat('/')) x /= parseFactor();
                    else return x;
                }
            }

            double parseFactor() {
                if (eat('+')) return +parseFactor();
                if (eat('-')) return -parseFactor();

                double x;
                int startPos = this.pos;
                if (eat('(')) {
                    x = parseExpression();
                    if (!eat(')')) throw new RuntimeException("Missing closing parenthesis");
                } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                    while ((ch >= '0' && ch <= '9') || ch == '.') nextChar();
                    x = Double.parseDouble(expression.substring(startPos, this.pos));
                } else {
                    throw new RuntimeException("Unexpected character: " + (char) ch);
                }

                if (eat('^')) x = Math.pow(x, parseFactor());

                return x;
            }
        }.parse();
    }
}
