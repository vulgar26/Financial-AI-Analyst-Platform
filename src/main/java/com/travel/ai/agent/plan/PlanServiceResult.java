package com.travel.ai.agent.plan;

import com.travel.ai.plan.PlanPhysicalStagePolicy;

/**
 * Result of PLAN stage proposal and Appendix-E parse normalization.
 *
 * <p>Pn2: also carries the resolved physical stage flags so the runtime
 * orchestrator does not re-parse the effective plan JSON a second time.</p>
 */
public record PlanServiceResult(
        String planJson,
        String planDraftSource,
        String planParseOutcome,
        int planParseAttempts,
        String planParseResolved,
        PlanPhysicalStagePolicy.PhysicalStageFlags physicalStageFlags
) {
    public PlanServiceResult {
        planJson = planJson != null ? planJson : "";
        planDraftSource = planDraftSource != null ? planDraftSource : "";
        planParseOutcome = planParseOutcome != null ? planParseOutcome : "";
        planParseResolved = planParseResolved != null ? planParseResolved : "";
        physicalStageFlags = physicalStageFlags != null
                ? physicalStageFlags
                : PlanPhysicalStagePolicy.PhysicalStageFlags.allStagesAfterPlan();
    }
}
