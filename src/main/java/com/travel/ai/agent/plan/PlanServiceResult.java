package com.travel.ai.agent.plan;

/**
 * Result of PLAN stage proposal and Appendix-E parse normalization.
 */
public record PlanServiceResult(
        String planJson,
        String planDraftSource,
        String planParseOutcome,
        int planParseAttempts,
        String planParseResolved
) {
    public PlanServiceResult {
        planJson = planJson != null ? planJson : "";
        planDraftSource = planDraftSource != null ? planDraftSource : "";
        planParseOutcome = planParseOutcome != null ? planParseOutcome : "";
        planParseResolved = planParseResolved != null ? planParseResolved : "";
    }
}
