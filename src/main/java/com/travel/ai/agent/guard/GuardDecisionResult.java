package com.travel.ai.agent.guard;

import com.travel.ai.runtime.PolicyEvent;

import java.util.List;

/**
 * Result of guard decision evaluation. It is pure data and does not mutate agent turn state.
 */
public record GuardDecisionResult(
        boolean skipLlm,
        String clarifyBody,
        String emptyHitsGateLogCode,
        RetrieveEmptyHitGate.Reason reason,
        List<PolicyEvent> policyEvents
) {
    public GuardDecisionResult {
        policyEvents = policyEvents == null ? List.of() : List.copyOf(policyEvents);
    }
}
