package com.agent.core.agent;

import com.agent.core.memory.Memory;
import com.agent.core.model.LLMResponse;
import com.agent.core.model.Message;
import com.agent.core.observer.AgentObserver;
import com.agent.core.tool.ToolRegistry;
import com.agent.core.llm.LLMClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Base class for all agent implementations.
 */
public abstract class BaseAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final LLMClient llmClient;
    protected final ToolRegistry toolRegistry;
    protected final Memory memory;
    protected final String systemPrompt;
    protected AgentObserver observer;

    protected BaseAgent(LLMClient llmClient, ToolRegistry toolRegistry, Memory memory, String systemPrompt) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.memory = memory;
        this.systemPrompt = systemPrompt;
    }

    /**
     * Set the observer for monitoring agent execution.
     *
     * @param observer the observer, or null to disable
     */
    public void setObserver(AgentObserver observer) {
        this.observer = observer;
    }

    /**
     * Get the current observer.
     *
     * @return the current observer, or null if none set
     */
    public AgentObserver getObserver() {
        return observer;
    }

    /**
     * Run the agent with the given user input.
     *
     * @param userInput the user's input message
     * @return the agent's result
     */
    public abstract AgentResult run(String userInput);

    /**
     * Get the current conversation memory.
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * Reset the agent's memory.
     */
    public void reset() {
        memory.clear();
    }

    /**
     * Call the LLM with the current memory and available tools.
     */
    protected LLMResponse callLLM() {
        List<Message> messages = memory.getMessages();
        return llmClient.chat(messages, toolRegistry.getDefinitions());
    }

    /**
     * Initialize memory with the system prompt if set.
     */
    protected void initSystemPrompt() {
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            memory.add(Message.system(systemPrompt));
        }
    }
}
