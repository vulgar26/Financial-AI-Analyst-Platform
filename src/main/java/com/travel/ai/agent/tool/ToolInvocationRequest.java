package com.travel.ai.agent.tool;

/**
 * Input for one governed tool invocation attempt.
 */
public record ToolInvocationRequest(
        String userMessage,
        String requestId,
        boolean weatherToolEnabled,
        boolean marketDataToolEnabled,
        int weatherSummaryMaxChars,
        int marketDataSummaryMaxChars
) {
}
