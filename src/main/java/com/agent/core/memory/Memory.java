package com.agent.core.memory;

import com.agent.core.model.Message;

import java.util.List;

/**
 * Interface for managing conversation memory.
 */
public interface Memory {

    /**
     * Add a message to memory.
     */
    void add(Message message);

    /**
     * Get all messages in memory.
     */
    List<Message> getMessages();

    /**
     * Clear all messages from memory.
     */
    void clear();

    /**
     * Get the number of messages in memory.
     */
    int size();
}
