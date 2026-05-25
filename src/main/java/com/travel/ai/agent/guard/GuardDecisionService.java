package com.travel.ai.agent.guard;

import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.PolicyStageAnchor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure guard decision service for the finance analyst mainline.
 *
 * <p>This service does not mutate TravelAgent turn state and does not emit SSE.
 * It returns the state values and policy events that the caller should apply.
 */
public final class GuardDecisionService {

    public GuardDecisionResult decide(GuardDecisionRequest request) {
        List<org.springframework.ai.document.Document> docs = request != null ? request.docs() : null;
        String toolPreface = request != null ? request.toolPreface() : null;
        String emptyHitsBehavior = request != null ? request.emptyHitsBehavior() : null;
        String requestId = request != null ? request.requestId() : null;

        RetrieveEmptyHitGate.Decision d = RetrieveEmptyHitGate.decide(docs, toolPreface, emptyHitsBehavior);
        String reason = d.reason() != null ? d.reason().name().toLowerCase(Locale.ROOT) : "";
        String behavior = d.skipLlm() ? "clarify" : "answer";
        int retrieveHitCount = docs != null ? docs.size() : 0;

        List<PolicyEvent> events = new ArrayList<>();
        events.add(PolicyEvent.of(
                        "rag_gate",
                        PolicyStageAnchor.POST_RETRIEVE.wireValue(),
                        behavior,
                        reason,
                        d.skipGateErrorCode(),
                        requestId
                )
                .withAttr("retrieve_hit_count", String.valueOf(retrieveHitCount))
                .withAttr("tool_payload_present", String.valueOf(ToolPrefacePayload.hasSubstantiveBody(toolPreface)))
                .withAttr("empty_hits_behavior", emptyHitsBehavior != null ? emptyHitsBehavior : "")
                .withAttr("skip_llm", String.valueOf(d.skipLlm()))
                .withAttr("reason", reason));

        if (shouldApplyMarketDataOutputGuard(toolPreface)) {
            events.add(PolicyEvent.of(
                            "finance_guard",
                            "guard",
                            "allow",
                            "market_data_mock_disclosure",
                            null,
                            requestId
                    )
                    .withAttr("workflow_id", "market_data_explain")
                    .withAttr("connector", "market_data")
                    .withAttr("mock_mode", "true")
                    .withAttr("freshness", "mock_non_realtime")
                    .withAttr("tradable", "false")
                    .withAttr("disclosure_required", "true")
                    .withAttr("investment_advice_allowed", "false"));
        }

        return new GuardDecisionResult(
                d.skipLlm(),
                d.clarifyBody() != null ? d.clarifyBody() : "",
                d.skipGateErrorCode(),
                d.reason(),
                events
        );
    }

    public static boolean shouldApplyMarketDataOutputGuard(String toolPreface) {
        if (toolPreface == null || toolPreface.isBlank()) {
            return false;
        }
        return toolPreface.contains("name=market_data")
                || toolPreface.contains("mockMode=true")
                || toolPreface.contains("mock_market_data=true");
    }
}
