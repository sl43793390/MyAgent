package com.agent.core.tool.builtin;

import com.agent.core.tool.RiskLevel;
import com.agent.core.tool.Tool;
import com.agent.core.tool.ToolDefinition;
import com.agent.core.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 计算一个算术表达式。
 *
 * <p>采用一个小型的递归下降解析器实现，而非使用表达式引擎或 {@code ScriptEngine}：由于输入来自模型生成的文本，
 * 求值器必须只能访问它正在解析的这些字符，不能触及任何其它东西。无法解析的内容会返回
 * {@link ToolResult#retryable(String)}，模型通常会在下一轮自行修正自己的语法。
 *
 * <p>支持 {@code + - * / ^} 以及括号。
 */
public class CalculatorTool implements Tool {

    private static final Logger log = LoggerFactory.getLogger(CalculatorTool.class);

    @Override
    public ToolDefinition getDefinition() {
        Map<String, Object> properties = Map.of(
                "expression", Map.of(
                        "type", "string",
                        "description", "The mathematical expression to evaluate (e.g., '2 + 2', '10 * (5 - 3)')"
                )
        );

        Map<String, Object> parameters = Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"expression"}
        );

        return new ToolDefinition(
                "calculator",
                "Evaluate a mathematical expression. Supports +, -, *, /, ^ and parentheses.",
                parameters
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        Object value = arguments.get("expression");
        String expression = value != null ? value.toString() : null;
        if (expression == null || expression.isBlank()) {
            return ToolResult.retryable("Error: 'expression' parameter is required");
        }

        try {
            double result = evaluate(expression);
            log.debug("Calculated: {} = {}", expression, result);
            return ToolResult.success(format(result));
        } catch (ArithmeticException e) {
            return ToolResult.failure("Error evaluating expression: " + e.getMessage());
        } catch (RuntimeException e) {
            return ToolResult.retryable("Error evaluating expression '" + expression + "': " + e.getMessage());
        }
    }

    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SAFE;
    }

    /**
     * 避免把 {@code 0.30000000000000004} 这类浮点噪声返回给模型。
     */
    private static String format(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static double evaluate(String expression) {
        return new Parser(expression).parse();
    }

    /**
     * 针对不可变输入字符串的递归下降求值器。
     *
     * <pre>
     * expression := term (('+' | '-') term)*
     * term       := factor (('*' | '/') factor)*
     * factor     := ('+' | '-') factor | '(' expression ')' | number
     * </pre>
     */
    private static final class Parser {

        private final String input;
        private int pos = -1;
        private int ch;

        private Parser(String input) {
            this.input = input;
        }

        private double parse() {
            nextChar();
            double value = parseExpression();
            skipWhitespace();
            if (pos < input.length()) {
                throw new IllegalArgumentException("unexpected character '" + (char) ch + "'");
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            for (;;) {
                skipWhitespace();
                if (eat('+')) {
                    value += parseTerm();
                } else if (eat('-')) {
                    value -= parseTerm();
                } else {
                    return value;
                }
            }
        }

        private double parseTerm() {
            double value = parseFactor();
            for (;;) {
                skipWhitespace();
                if (eat('*')) {
                    value *= parseFactor();
                } else if (eat('/')) {
                    double divisor = parseFactor();
                    if (divisor == 0.0) {
                        throw new ArithmeticException("division by zero");
                    }
                    value /= divisor;
                } else {
                    return value;
                }
            }
        }

        private double parseFactor() {
            skipWhitespace();
            if (eat('+')) {
                return parseFactor();
            }
            if (eat('-')) {
                return -parseFactor();
            }

            double value;
            int startPos = pos;
            if (eat('(')) {
                value = parseExpression();
                skipWhitespace();
                if (!eat(')')) {
                    throw new IllegalArgumentException("missing closing parenthesis");
                }
            } else if ((ch >= '0' && ch <= '9') || ch == '.') {
                while ((ch >= '0' && ch <= '9') || ch == '.') {
                    nextChar();
                }
                String number = input.substring(startPos, pos);
                try {
                    value = Double.parseDouble(number);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("malformed number '" + number + "'");
                }
            } else {
                throw new IllegalArgumentException(pos >= input.length()
                        ? "unexpected end of expression"
                        : "unexpected character '" + (char) ch + "'");
            }

            skipWhitespace();
            if (eat('^')) {
                value = Math.pow(value, parseFactor());
            }
            return value;
        }

        private void skipWhitespace() {
            while (ch != -1 && Character.isWhitespace(ch)) {
                nextChar();
            }
        }

        private void nextChar() {
            ch = (++pos < input.length()) ? input.charAt(pos) : -1;
        }

        private boolean eat(int charToEat) {
            if (ch == charToEat) {
                nextChar();
                return true;
            }
            return false;
        }
    }
}
