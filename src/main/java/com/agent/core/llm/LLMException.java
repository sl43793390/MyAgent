package com.agent.core.llm;

/**
 * 表示对大模型服务商的调用失败。
 *
 * <p>在引入这套异常体系之前，所有服务商错误都被简单地包进一个
 * {@code RuntimeException("Failed to call OpenAI API")}。调用方无法区分「你的密钥错误」（应立即停止并告警人工）、
 * 「触发了限流」（退避后重试）与「模型拒绝了格式错误的工具消息」（我们自身代码的缺陷）这三种情况。三者表现
 * 完全一致，导致调用方要么对一切重试，要么完全不重试。
 *
 * <p>子类承载了这种区分，而 {@link #retryable()} 回答了通用重试循环真正需要的唯一问题。
 *
 * <pre>{@code
 * try {
 *     return llmClient.chat(messages, tools, params);
 * } catch (LLMException.RateLimit e) {
 *     // 退避后重试
 * } catch (LLMException.Authentication e) {
 *     // 配置错误——快速失败，不要重试
 * }
 * }</pre>
 */
public class LLMException extends RuntimeException {

    public LLMException(String message) {
        super(message);
    }

    public LLMException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 判断重新发起相同请求是否有可能成功。
     */
    public boolean retryable() {
        return false;
    }

    /** 服务商拒绝了 API 密钥，或密钥无权访问该模型。不可重试。 */
    public static final class Authentication extends LLMException {
        public Authentication(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * 请求本身因格式错误被拒绝——例如模型名称无效、上下文超出窗口长度，
     * 或服务商认为对话非法（比如出现了没有前置工具调用的工具结果）。对相同请求重试仍会失败。
     */
    public static final class InvalidRequest extends LLMException {
        public InvalidRequest(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 账户或部署触发了限流。可重试，最好配合退避策略。 */
    public static final class RateLimit extends LLMException {
        public RateLimit(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** 服务商返回了 5xx 错误。可重试。 */
    public static final class Server extends LLMException {
        public Server(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** 调用未在配置的超时时间内完成。可重试。 */
    public static final class Timeout extends LLMException {
        public Timeout(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }

    /** 传输层故障：DNS、TLS、连接重置、套接字关闭等。可重试。 */
    public static final class Network extends LLMException {
        public Network(String message, Throwable cause) {
            super(message, cause);
        }

        @Override
        public boolean retryable() {
            return true;
        }
    }
}
