package com.travel.ai.agent.plan;

/**
 * Input values for one PLAN stage execution.
 */
public record PlanServiceRequest(
        String userMessage,
        String requestId,
        boolean planStageEnabled
) {
}
