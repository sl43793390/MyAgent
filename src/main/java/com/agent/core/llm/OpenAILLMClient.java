package com.agent.core.llm;

import com.agent.core.model.*;
import com.agent.core.observer.AgentObserver;
import com.agent.core.tool.ToolDefinition;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.FunctionDefinition;
import com.openai.models.FunctionParameters;
import com.openai.models.chat.completions.*;
import com.openai.models.completions.CompletionUsage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * OpenAI LLM client implementation using the official OpenAI Java SDK.
 * Supports OpenAI and compatible APIs.
 */
public class OpenAILLMClient implements LLMClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAILLMClient.class);

    private final OpenAIClient client;
    private final String model;
    private AgentObserver observer;

    /**
     * Create a client with the official OpenAI SDK.
     *
     * @param client the OpenAI client
     * @param model  the model name to use
     */
    public OpenAILLMClient(OpenAIClient client, String model) {
        this.client = client;
        this.model = model;
    }

    /**
     * Create a client for OpenAI API.
     *
     * @param apiKey the API key
     * @param model  the model name
     * @return the LLM client
     */
    public static OpenAILLMClient openAI(String apiKey, String model) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
        return new OpenAILLMClient(client, model);
    }

    /**
     * Create a client for OpenAI API with custom base URL.
     *
     * @param apiKey  the API key
     * @param baseUrl the base URL
     * @param model   the model name
     * @return the LLM client
     */
    public static OpenAILLMClient openAI(String apiKey, String baseUrl, String model) {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return new OpenAILLMClient(client, model);
    }

    /**
     * Create a client from environment variables.
     * Reads OPENAI_API_KEY from environment.
     *
     * @param model the model name
     * @return the LLM client
     */
    public static OpenAILLMClient fromEnv(String model) {
        OpenAIClient client = OpenAIOkHttpClient.fromEnv();
        return new OpenAILLMClient(client, model);
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
        List<ToolDefinition> effectiveTools = tools != null ? tools : List.of();
        LLMParams effectiveParams = params != null ? params : LLMParams.DEFAULT;
        long startTime = System.currentTimeMillis();

        try {
            if (observer != null) {
                observer.onLLMCallStart(messages, effectiveTools);
            }

            log.debug("Sending request to OpenAI: model={}, messages={}, tools={}",
                    model, messages.size(), effectiveTools.size());

            ChatCompletion completion = client.chat().completions()
                    .create(buildRequest(messages, effectiveTools, effectiveParams));

            LLMResponse response = mapResponse(completion);

            log.debug("Received response from OpenAI: id={}, finishReason={}, usage={}",
                    response.id(), response.finishReason(), response.usage());

            if (observer != null) {
                observer.onLLMCallEnd(response, System.currentTimeMillis() - startTime);
            }

            return response;

        } catch (Exception e) {
            if (observer != null) {
                observer.onLLMCallError(e, System.currentTimeMillis() - startTime);
            }

            log.error("Failed to call OpenAI API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
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
        if (params.stop() != null && !params.stop().isEmpty()) {
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
                    .map(tc -> ChatCompletionMessageToolCall.ofFunction(
                            ChatCompletionMessageFunctionToolCall.builder()
                                    .id(tc.id())
                                    .function(ChatCompletionMessageFunctionToolCall.Function.builder()
                                            .name(tc.name())
                                            .arguments(tc.arguments() != null ? tc.arguments() : "{}")
                                            .build())
                                    .build()))
                    .toList());
        }

        return builder.build();
    }

    private ChatCompletionFunctionTool mapTool(ToolDefinition tool) {
        Map<String, Object> schema = tool.parameters() != null ? tool.parameters() : Map.of();

        FunctionParameters.Builder parameters = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from(schema.getOrDefault("type", "object")))
                .putAdditionalProperty("properties",
                        JsonValue.from(schema.getOrDefault("properties", Map.of())));

        if (schema.containsKey("required")) {
            parameters.putAdditionalProperty("required", JsonValue.from(schema.get("required")));
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
            throw new IllegalStateException("No choices in OpenAI response");
        }

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();

        String content = message.content().orElse(null);

        List<ToolCall> toolCalls = message.toolCalls()
                .map(calls -> calls.stream()
                        .filter(ChatCompletionMessageToolCall::isFunction)
                        .map(tc -> {
                            ChatCompletionMessageFunctionToolCall function = tc.asFunction();
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
