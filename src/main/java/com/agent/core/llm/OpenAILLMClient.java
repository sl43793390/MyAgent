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

import java.util.ArrayList;
import java.util.List;

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
        long startTime = System.currentTimeMillis();

        try {
            // Notify observer before LLM call
            if (observer != null) {
                observer.onLLMCallStart(messages, tools != null ? tools : List.of());
            }

            ChatCompletionCreateParams.Builder paramsBuilder = ChatCompletionCreateParams.builder()
                    .model(model)
                    .temperature(0.7)
                    .maxCompletionTokens(4096);

            // Add messages
            for (Message message : messages) {
                paramsBuilder.addMessage(mapMessage(message));
            }

            // Add tools if present
            if (tools != null && !tools.isEmpty()) {
                for (ToolDefinition tool : tools) {
                    paramsBuilder.addTool(mapTool(tool));
                }
            }

            ChatCompletionCreateParams params = paramsBuilder.build();

            log.debug("Sending request to OpenAI with {} messages and {} tools",
                    messages.size(), tools != null ? tools.size() : 0);

            ChatCompletion completion = client.chat().completions().create(params);

            log.debug("Received response from OpenAI");

            LLMResponse response = mapToLLMResponse(completion);

            // Notify observer after LLM call
            if (observer != null) {
                long duration = System.currentTimeMillis() - startTime;
                observer.onLLMCallEnd(response, duration);
            }

            return response;

        } catch (Exception e) {
            // Notify observer on error
            if (observer != null) {
                long duration = System.currentTimeMillis() - startTime;
                observer.onLLMCallError(e, duration);
            }

            log.error("Failed to call OpenAI API: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    private ChatCompletionMessageParam mapMessage(Message message) {
        switch (message.role()) {
            case SYSTEM:
                return ChatCompletionMessageParam.ofSystem(
                        ChatCompletionSystemMessageParam.builder()
                                .content(message.content() != null ? message.content() : "")
                                .build()
                );

            case USER:
                return ChatCompletionMessageParam.ofUser(
                        ChatCompletionUserMessageParam.builder()
                                .content(message.content() != null ? message.content() : "")
                                .build()
                );

            case ASSISTANT:
                ChatCompletionAssistantMessageParam.Builder assistantBuilder =
                        ChatCompletionAssistantMessageParam.builder();

                if (message.content() != null) {
                    assistantBuilder.content(message.content());
                }

                if (message.hasToolCalls()) {
                    List<ChatCompletionMessageToolCall> toolCalls = new ArrayList<>();
                    for (ToolCall tc : message.toolCalls()) {
                        // Build function call using ChatCompletionMessageFunctionToolCall
                        ChatCompletionMessageFunctionToolCall.Function function =
                                ChatCompletionMessageFunctionToolCall.Function.builder()
                                        .name(tc.name())
                                        .arguments(tc.arguments())
                                        .build();

                        ChatCompletionMessageFunctionToolCall functionToolCall =
                                ChatCompletionMessageFunctionToolCall.builder()
                                        .id(tc.id())
                                        .function(function)
                                        .build();

                        // Wrap in ChatCompletionMessageToolCall union type
                        ChatCompletionMessageToolCall toolCall =
                                ChatCompletionMessageToolCall.ofFunction(functionToolCall);

                        toolCalls.add(toolCall);
                    }
                    assistantBuilder.toolCalls(toolCalls);
                }

                return ChatCompletionMessageParam.ofAssistant(assistantBuilder.build());

            case TOOL:
                return ChatCompletionMessageParam.ofTool(
                        ChatCompletionToolMessageParam.builder()
                                .content(message.content() != null ? message.content() : "")
                                .toolCallId(message.toolCallId() != null ? message.toolCallId() : "")
                                .build()
                );

            default:
                throw new IllegalArgumentException("Unknown message role: " + message.role());
        }
    }

    private ChatCompletionFunctionTool mapTool(ToolDefinition tool) {
        // Build function parameters from JSON schema
        FunctionParameters.Builder paramsBuilder = FunctionParameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(tool.parameters().get("properties")))
                .putAdditionalProperty("required", JsonValue.from(tool.parameters().get("required")));

        FunctionDefinition functionDef = FunctionDefinition.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(paramsBuilder.build())
                .build();

        return ChatCompletionFunctionTool.builder()
                .function(functionDef)
                .build();
    }

    private LLMResponse mapToLLMResponse(ChatCompletion completion) {
        if (completion.choices() == null || completion.choices().isEmpty()) {
            throw new RuntimeException("No choices in OpenAI response");
        }

        ChatCompletion.Choice choice = completion.choices().get(0);
        ChatCompletionMessage message = choice.message();

        // Extract tool calls
        List<ToolCall> toolCalls = null;
        if (message.toolCalls().isPresent() && !message.toolCalls().get().isEmpty()) {
            toolCalls = message.toolCalls().get().stream()
                    .filter(ChatCompletionMessageToolCall::isFunction)
                    .map(tc -> {
                        ChatCompletionMessageFunctionToolCall functionToolCall = tc.asFunction();
                        return new ToolCall(
                                functionToolCall.id(),
                                functionToolCall.function().name(),
                                functionToolCall.function().arguments()
                        );
                    })
                    .toList();
        }

        // Extract content
        String content = message.content().isPresent() ? message.content().get() : null;

        // Create our Message
        Message ourMessage = new Message(
                Role.ASSISTANT,
                content,
                null,
                null,
                toolCalls
        );

        // Extract token usage
        long promptTokens = 0;
        long completionTokens = 0;
        if (completion.usage().isPresent()) {
            CompletionUsage usage = completion.usage().get();
            promptTokens = usage.promptTokens();
            completionTokens = usage.completionTokens();
        }

        return new LLMResponse(ourMessage, (int) promptTokens, (int) completionTokens);
    }
}
