package com.agent.core.tool.builtin;

import com.agent.core.security.PathSandbox;

import java.nio.file.Path;
import java.util.Map;

/**
 * 内置工具的公共参数提取方法。
 *
 * <p>每个参数都是从模型生成的 JSON 解码得到的 {@code Map<String, Object>}，因此「字符串、整数、布尔」
 * 这三种转换被重复拷贝进了五个不同的工具。这里将它们统一实现一次，并对实际会出现的情况保持
 * 一致的行为：以字符串形式传来的数字、以 {@code "true"} 形式传来的布尔值、缺失的值等。
 */
final class Args {

    private Args() {
    }

    /**
     * @return 去除首尾空白后的字符串值；若参数缺失或为空串则返回 null
     */
    static String string(Map<String, Object> arguments, String name) {
        Object value = arguments.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    /**
     * 在沙箱范围内解析一个路径。
     *
     * @throws SecurityException 当路径超出沙箱边界时
     */
    static Path path(PathSandbox sandbox, Map<String, Object> arguments, String name) {
        String raw = string(arguments, name);
        if (raw == null) {
            throw new SecurityException("'" + name + "' parameter is required");
        }
        return sandbox.resolve(raw, name);
    }

    static int integer(Map<String, Object> arguments, String name, int defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    static boolean bool(Map<String, Object> arguments, String name, boolean defaultValue) {
        Object value = arguments.get(name);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Boolean flag) {
            return flag;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    /**
     * 截断工具的输出，避免单个异常文件撑爆上下文窗口。
     */
    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength
                ? text.substring(0, maxLength) + "\n... [truncated, " + text.length() + " chars total]"
                : text;
    }
}
