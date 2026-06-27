package com.agent.core.llm;

import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.observer.AgentObserver;
import com.agent.core.tool.ToolDefinition;

import java.util.List;

/**
 * Interface for interacting with Large Language Models.
 */
public interface LLMClient {

    /**
     * Send messages to the LLM and get a response.
     *
     * @param messages the conversation history
     * @param tools    available tools for the LLM to use
     * @return the LLM response
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools);

    /**
     * Send messages to the LLM and get a response with custom parameters.
     *
     * @param messages the conversation history
     * @param tools    available tools for the LLM to use
     * @param params   custom LLM parameters (temperature, topP, etc.)
     * @return the LLM response
     */
    LLMResponse chat(List<Message> messages, List<ToolDefinition> tools, LLMParams params);

    /**
     * Send messages to the LLM without tools.
     *
     * @param messages the conversation history
     * @return the LLM response
     */
    default LLMResponse chat(List<Message> messages) {
        return chat(messages, List.of());
    }

    /**
     * Set the observer for monitoring LLM calls.
     *
     * @param observer the observer, or null to disable
     */
    void setObserver(AgentObserver observer);

    /**
     * Get the current observer.
     *
     * @return the current observer, or null if none set
     */
    AgentObserver getObserver();
}
