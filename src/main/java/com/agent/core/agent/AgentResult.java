package com.agent.core.agent;

/**
 * Result of an agent execution.
 *
 * @param output       the final output text
 * @param totalSteps   number of steps the agent took
 * @param totalTokens  total tokens consumed
 */
public record AgentResult(
        String output,
        int totalSteps,
        int totalTokens
) {
}
