package com.agent.core.llm;

import com.agent.core.model.*;
import com.agent.core.observer.AgentObserver;
import com.agent.core.tool.ToolDefinition;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.BadRequestException;
import com.openai.errors.InternalServerException;
import com.openai.errors.NotFoundException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.PermissionDeniedException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 基于官方 OpenAI Java SDK 的大模型客户端。兼容 OpenAI 及任意 OpenAI 兼容端点
 * （Azure OpenAI 网关、vLLM、Ollama、One-API 代理等）。
 *
 * <p>主要特性：
 * <ul>
 *   <li><b>错误类型化。</b>服务商错误会被转换为 {@link LLMException} 的子类，
 *       使调用方能够区分无效密钥与限流。</li>
 *   <li><b>超时与重试显式可控</b>，而非沿用 SDK 的默认行为，从而避免卡住的请求
 *       让智能体运行无限期挂起。</li>
 *   <li><b>完整转发 JSON Schema。</b>{@link ToolDefinition} 模式中的任意字段
 *       （{@code enum}、{@code minimum}、{@code items}、嵌套对象等）都会发送给服务商，
 *       而不会被静默丢弃。</li>
 * </ul>
 *
 * <p>本类线程安全，设计为单例共享。
 */
public class OpenAILLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAILLMClient.class);

    /** 默认的整个请求超时时间。推理模型耗时较长是合理的。 */
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** SDK 针对可重试状态码执行的默认重试次数。 */
    public static final int DEFAULT_MAX_RETRIES = 2;

    private final OpenAIClient client;
    private final String model;
    private volatile AgentObserver observer;

    /**
     * 基于一个已配置好的 SDK 客户端创建客户端。
     *
     * @param client 已配置好的 OpenAI 客户端
     * @param model  要使用的模型名称
     */
    public OpenAILLMClient(OpenAIClient client, String model) {
        if (client == null) {
            throw new IllegalArgumentException("OpenAIClient must not be null");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.client = client;
        this.model = model;
    }

    /**
     * 创建访问 OpenAI API 的客户端。
     */
    public static OpenAILLMClient openAI(String apiKey, String model) {
        return new OpenAILLMClient(buildClient(apiKey, null, DEFAULT_TIMEOUT, DEFAULT_MAX_RETRIES), model);
    }

    /**
     * 创建一个使用自定义 base URL 的 OpenAI 兼容端点客户端。
     */
    public static OpenAILLMClient openAI(String apiKey, String baseUrl, String model) {
        return new OpenAILLMClient(buildClient(apiKey, baseUrl, DEFAULT_TIMEOUT, DEFAULT_MAX_RETRIES), model);
    }

    /**
     * 创建一个显式指定请求超时和重试次数的客户端。
     *
     * @param apiKey     API 密钥
     * @param baseUrl    base URL；传 null 表示使用官方 OpenAI 端点
     * @param model      模型名称
     * @param timeout    整个请求的超时时间
     * @param maxRetries SDK 针对可重试响应执行的重试次数
     */
    public static OpenAILLMClient openAI(String apiKey, String baseUrl, String model,
                                         Duration timeout, int maxRetries) {
        return new OpenAILLMClient(buildClient(apiKey, baseUrl, timeout, maxRetries), model);
    }

    /**
     * 通过环境变量创建客户端（{@code OPENAI_API_KEY}，可选 {@code OPENAI_BASE_URL}）。
     */
    public static OpenAILLMClient fromEnv(String model) {
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .fromEnv()
                .timeout(DEFAULT_TIMEOUT)
                .maxRetries(DEFAULT_MAX_RETRIES);
        return new OpenAILLMClient(builder.build(), model);
    }

    /**
     * 本客户端发送请求所使用的模型。
     */
    public String model() {
        return model;
    }

    @Override
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    @Override
    public AgentObserver getObserver() {
        return observer;
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return chat(messages, tools, LLMParams.DEFAULT);
    }

    @Override
    public LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, LLMParams params) {
        if (messages == null || messages.isEmpty()) {
            throw new LLMException.InvalidRequest("At least one message is required", null);
        }

        List<ToolDefinition> effectiveTools = tools != null ? tools : List.of();
        LLMParams effectiveParams = params != null ? params : LLMParams.DEFAULT;
        long startTime = System.currentTimeMillis();
        AgentObserver currentObserver = observer;

        if (currentObserver != null) {
            currentObserver.onLLMCallStart(messages, effectiveTools);
        }

        log.debug("Sending request to OpenAI: model={}, messages={}, tools={}",
                model, messages.size(), effectiveTools.size());

        try {
            ChatCompletion completion = client.chat().completions()
                    .create(buildRequest(messages, effectiveTools, effectiveParams));

            LLMResponse response = mapResponse(completion);

            log.debug("Received response from OpenAI: id={}, finishReason={}, usage={}",
                    response.id(), response.finishReason(), response.usage());

            if (currentObserver != null) {
                currentObserver.onLLMCallEnd(response, System.currentTimeMillis() - startTime);
            }
            return response;

        } catch (LLMException e) {
            notifyError(currentObserver, e, startTime);
            throw e;
        } catch (Exception e) {
            LLMException translated = translate(e);
            log.error("LLM call failed ({}): {}", translated.getClass().getSimpleName(), translated.getMessage());
            notifyError(currentObserver, translated, startTime);
            throw translated;
        }
    }

    private static OpenAIClient buildClient(String apiKey, String baseUrl, Duration timeout, int maxRetries) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        OpenAIOkHttpClient.Builder builder = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(timeout != null ? timeout : DEFAULT_TIMEOUT)
                .maxRetries(Math.max(0, maxRetries));
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl);
        }
        return builder.build();
    }

    private static void notifyError(AgentObserver observer, Exception error, long startTime) {
        if (observer != null) {
            observer.onLLMCallError(error, System.currentTimeMillis() - startTime);
        }
    }

    /**
     * 将 SDK/服务商的错误映射到 {@link LLMException} 异常体系。映射按从最具体到最通用的顺序
     * 排列，因为多个 SDK 异常继承自同一个基类。
     */
    private static LLMException translate(Exception error) {
        if (error instanceof LLMException llmException) {
            return llmException;
        }
        if (error instanceof UnauthorizedException || error instanceof PermissionDeniedException) {
            return new LLMException.Authentication("Authentication failed: " + error.getMessage(), error);
        }
        if (error instanceof RateLimitException) {
            return new LLMException.RateLimit("Rate limited: " + error.getMessage(), error);
        }
        if (error instanceof BadRequestException || error instanceof NotFoundException) {
            return new LLMException.InvalidRequest("Invalid request: " + error.getMessage(), error);
        }
        if (error instanceof InternalServerException) {
            return new LLMException.Server("Provider error: " + error.getMessage(), error);
        }
        if (error instanceof OpenAIServiceException) {
            // 其它被作为服务异常暴露出来的 4xx/5xx：按服务端错误处理并视为可重试，
            // 这是无法区分 4xx 与 5xx 时的安全默认选择。
            return new LLMException.Server("Provider error: " + error.getMessage(), error);
        }
        if (error instanceof OpenAIIoException) {
            return new LLMException.Network("Network error: " + error.getMessage(), error);
        }
        if (error instanceof OpenAIRetryableException) {
            return new LLMException.Timeout("Request did not complete: " + error.getMessage(), error);
        }
        return new LLMException("LLM call failed: " + error.getMessage(), error);
    }

    private ChatCompletionCreateParams buildRequest(List<Message> messages,
                                                    List<ToolDefinition> tools,
                                                    LLMParams params) {
        ChatCompletionCreateParams.Builder builder = ChatCompletionCreateParams.builder()
                .model(model)
                .temperature(params.effectiveTemperature())
                .maxCompletionTokens(params.effectiveMaxCompletionTokens());

        if (params.topP() != null) {
            builder.topP(params.topP());
        }
        if (params.frequencyPenalty() != null) {
            builder.frequencyPenalty(params.frequencyPenalty());
        }
        if (params.presencePenalty() != null) {
            builder.presencePenalty(params.presencePenalty());
        }
        if (params.seed() != null) {
            builder.seed(params.seed());
        }
        if (params.stop() != null && !params.stop().isBlank()) {
            builder.stop(params.stop());
        }

        messages.forEach(message -> builder.addMessage(mapMessage(message)));
        tools.forEach(tool -> builder.addTool(mapTool(tool)));

        return builder.build();
    }

    private ChatCompletionMessageParam mapMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> ChatCompletionMessageParam.ofSystem(
                    ChatCompletionSystemMessageParam.builder()
                            .content(orEmpty(message.content()))
                            .build()
            );
            case USER -> ChatCompletionMessageParam.ofUser(
                    ChatCompletionUserMessageParam.builder()
                            .content(orEmpty(message.content()))
                            .build()
            );
            case ASSISTANT -> ChatCompletionMessageParam.ofAssistant(mapAssistantMessage(message));
            case TOOL -> ChatCompletionMessageParam.ofTool(
                    ChatCompletionToolMessageParam.builder()
                            .content(orEmpty(message.content()))
                            .toolCallId(orEmpty(message.toolCallId()))
                            .build()
            );
        };
    }

    private ChatCompletionAssistantMessageParam mapAssistantMessage(Message message) {
        ChatCompletionAssistantMessageParam.Builder builder =
                ChatCompletionAssistantMessageParam.builder();

        if (message.content() != null) {
            builder.content(message.content());
        }

        if (message.hasToolCalls()) {
            builder.toolCalls(message.toolCalls().stream()
                    .map(toolCall -> ChatCompletionMessageToolCall.ofFunction(
                            ChatCompletionMessageFunctionToolCall.builder()
                                    .id(toolCall.id())
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(toolCall.name())
                                            .arguments(toolCall.arguments() != null
                                                    ? toolCall.arguments() : "{}")
                                            .build())
                                    .build()))
                    .toList());
        }

        return builder.build();
    }

    /**
     * 原样转发工具的 JSON Schema。
     *
     * <p>早期版本仅复制了 {@code type}、{@code properties} 和 {@code required}，
     * 导致 {@code enum}、{@code minimum} 或嵌套 {@code items} 等约束在模型看到之前就被丢弃——
     * 静默地削弱了模型所提供参数的校验。
     */
    private ChatCompletionFunctionTool mapTool(ToolDefinition tool) {
        Map<String, Object> schema = tool.parameters() != null ? tool.parameters() : Map.of();

        FunctionParameters.Builder parameters = FunctionParameters.builder();
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            parameters.putAdditionalProperty(entry.getKey(), JsonValue.from(entry.getValue()));
        }

        return ChatCompletionFunctionTool.builder()
                .function(FunctionDefinition.builder()
                        .name(tool.name())
                        .description(tool.description())
                        .parameters(parameters.build())
                        .build())
                .build();
    }

    private LLMResponse mapResponse(ChatCompletion completion) {
        if (completion.choices() == null || completion.choices().isEmpty()) {
            throw new LLMException("Provider returned a response with no choices");
        }

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();
        if (message == null) {
            throw new LLMException("Provider returned a choice with no message");
        }

        String content = message.content().orElse(null);

        List<ToolCall> toolCalls = message.toolCalls()
                .map(calls -> calls.stream()
                        .filter(ChatCompletionMessageToolCall::isFunction)
                        .map(toolCall -> {
                            ChatCompletionMessageFunctionToolCall function = toolCall.asFunction();
                            return new ToolCall(
                                    function.id(),
                                    function.function().name(),
                                    function.function().arguments()
                            );
                        })
                        .toList())
                .orElse(List.of());

        Message assistantMessage = new Message(Role.ASSISTANT, content, null, null, toolCalls);

        return new LLMResponse(
                assistantMessage,
                completion.id(),
                completion.model(),
                choice.finishReason() != null ? choice.finishReason().asString() : null,
                mapUsage(completion)
        );
    }

    private TokenUsage mapUsage(ChatCompletion completion) {
        return completion.usage()
                .map(usage -> new TokenUsage(
                        usage.promptTokens(),
                        usage.completionTokens(),
                        usage.totalTokens(),
                        usage.promptTokensDetails()
                                .flatMap(CompletionUsage.PromptTokensDetails::cachedTokens)
                                .orElse(0L),
                        usage.completionTokensDetails()
                                .flatMap(CompletionUsage.CompletionTokensDetails::reasoningTokens)
                                .orElse(0L)
                ))
                .orElse(TokenUsage.NONE);
    }

    private static String orEmpty(String value) {
        return value != null ? value : "";
    }
}
