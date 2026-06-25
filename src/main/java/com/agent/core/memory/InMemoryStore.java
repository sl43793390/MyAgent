package com.agent.core.memory;

import com.agent.core.model.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory implementation of conversation memory.
 */
public class InMemoryStore implements Memory {

    private final List<Message> messages = new ArrayList<>();
    private final int maxMessages;

    /**
     * Create memory with unlimited capacity.
     */
    public InMemoryStore() {
        this.maxMessages = Integer.MAX_VALUE;
    }

    /**
     * Create memory with a maximum number of messages to retain.
     * When exceeded, the oldest messages are removed (keeping system messages).
     *
     * @param maxMessages maximum number of messages to retain
     */
    public InMemoryStore(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public synchronized void add(Message message) {
        messages.add(message);
        trimIfNeeded();
    }

    @Override
    public synchronized List<Message> getMessages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    @Override
    public synchronized void clear() {
        messages.clear();
    }

    @Override
    public synchronized int size() {
        return messages.size();
    }

    private void trimIfNeeded() {
        if (messages.size() <= maxMessages) {
            return;
        }

        // Keep system messages and trim non-system messages from the beginning
        List<Message> systemMessages = new ArrayList<>();
        List<Message> nonSystemMessages = new ArrayList<>();

        for (Message msg : messages) {
            if (msg.role() == com.agent.core.model.Role.SYSTEM) {
                systemMessages.add(msg);
            } else {
                nonSystemMessages.add(msg);
            }
        }

        // Remove oldest non-system messages
        int excess = nonSystemMessages.size() - (maxMessages - systemMessages.size());
        if (excess > 0) {
            nonSystemMessages = nonSystemMessages.subList(excess, nonSystemMessages.size());
        }

        messages.clear();
        messages.addAll(systemMessages);
        messages.addAll(nonSystemMessages);
    }
}
