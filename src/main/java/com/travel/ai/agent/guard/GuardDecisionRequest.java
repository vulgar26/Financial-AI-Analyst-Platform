package com.travel.ai.agent.guard;

import org.springframework.ai.document.Document;

import java.util.List;

/**
 * Input for guard decision evaluation.
 */
public record GuardDecisionRequest(
        List<Document> docs,
        String toolPreface,
        String emptyHitsBehavior,
        String requestId
) {
}
