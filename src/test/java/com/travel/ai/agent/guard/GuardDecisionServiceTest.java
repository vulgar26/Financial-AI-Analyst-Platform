package com.travel.ai.agent.guard;

import com.travel.ai.runtime.PolicyEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GuardDecisionServiceTest {

    private final GuardDecisionService service = new GuardDecisionService();

    @Test
    void emptyDocsWithoutToolPayload_skipsLlmAndEmitsRagGatePolicy() {
        GuardDecisionResult result = service.decide(new GuardDecisionRequest(
                List.of(),
                "",
                "clarify",
                "req-empty"
        ));

        assertThat(result.skipLlm()).isTrue();
        assertThat(result.clarifyBody()).contains(RetrieveEmptyHitGate.ERROR_CODE_RETRIEVE_EMPTY);
        assertThat(result.emptyHitsGateLogCode()).isEqualTo(RetrieveEmptyHitGate.ERROR_CODE_RETRIEVE_EMPTY);
        assertThat(result.reason()).isEqualTo(RetrieveEmptyHitGate.Reason.APPLIED_CLARIFY_RAG_EMPTY);

        PolicyEvent rag = result.policyEvents().get(0);
        assertThat(rag.policyType()).isEqualTo("rag_gate");
        assertThat(rag.stage()).isEqualTo("post_retrieve");
        assertThat(rag.behavior()).isEqualTo("clarify");
        assertThat(rag.ruleId()).isEqualTo("applied_clarify_rag_empty");
        assertThat(rag.errorCode()).isEqualTo(RetrieveEmptyHitGate.ERROR_CODE_RETRIEVE_EMPTY);
        assertThat(rag.requestId()).isEqualTo("req-empty");
        assertThat(rag.attrs()).containsEntry("retrieve_hit_count", "0")
                .containsEntry("tool_payload_present", "false")
                .containsEntry("empty_hits_behavior", "clarify")
                .containsEntry("skip_llm", "true")
                .containsEntry("reason", "applied_clarify_rag_empty");
    }

    @Test
    void docsPresent_allowsLlmAndEmitsRagGatePolicy() {
        GuardDecisionResult result = service.decide(new GuardDecisionRequest(
                List.of(new Document("hit")),
                "",
                "clarify",
                "req-docs"
        ));

        assertThat(result.skipLlm()).isFalse();
        assertThat(result.emptyHitsGateLogCode()).isNull();
        assertThat(result.reason()).isEqualTo(RetrieveEmptyHitGate.Reason.SKIPPED_HAS_RETRIEVAL_HITS);
        assertThat(result.policyEvents()).hasSize(1);
        assertThat(result.policyEvents().get(0).attrs()).containsEntry("retrieve_hit_count", "1")
                .containsEntry("skip_llm", "false")
                .containsEntry("reason", "skipped_has_retrieval_hits");
    }

    @Test
    void toolPayloadPresent_allowsLlmAndEmitsRagGatePolicy() {
        String toolPreface = """
                name=weather outcome=OK
                BEGIN_TOOL_DATA
                useful observation
                END_TOOL_DATA
                """;

        GuardDecisionResult result = service.decide(new GuardDecisionRequest(
                List.of(),
                toolPreface,
                "clarify",
                "req-tool"
        ));

        assertThat(result.skipLlm()).isFalse();
        assertThat(result.reason()).isEqualTo(RetrieveEmptyHitGate.Reason.SKIPPED_TOOL_SUBSTANTIVE_PAYLOAD);
        assertThat(result.policyEvents()).hasSize(1);
        assertThat(result.policyEvents().get(0).attrs()).containsEntry("tool_payload_present", "true")
                .containsEntry("reason", "skipped_tool_substantive_payload");
    }

    @Test
    void marketDataToolPreface_emitsFinanceGuardPolicy() {
        String toolPreface = """
                name=market_data outcome=OK
                BEGIN_TOOL_DATA
                mock market data
                END_TOOL_DATA
                """;

        GuardDecisionResult result = service.decide(new GuardDecisionRequest(
                List.of(),
                toolPreface,
                "clarify",
                "req-market"
        ));

        assertThat(result.policyEvents()).extracting(PolicyEvent::policyType)
                .containsExactly("rag_gate", "finance_guard");
        PolicyEvent finance = result.policyEvents().get(1);
        assertThat(finance.stage()).isEqualTo("guard");
        assertThat(finance.behavior()).isEqualTo("allow");
        assertThat(finance.ruleId()).isEqualTo("market_data_mock_disclosure");
        assertThat(finance.errorCode()).isNull();
        assertThat(finance.requestId()).isEqualTo("req-market");
        assertThat(finance.attrs()).containsEntry("workflow_id", "market_data_explain")
                .containsEntry("connector", "market_data")
                .containsEntry("mock_mode", "true")
                .containsEntry("freshness", "mock_non_realtime")
                .containsEntry("tradable", "false")
                .containsEntry("disclosure_required", "true")
                .containsEntry("investment_advice_allowed", "false");
    }

    @Test
    void nonMarketDataToolPreface_doesNotEmitFinanceGuardPolicy() {
        String toolPreface = """
                name=weather outcome=OK
                BEGIN_TOOL_DATA
                weather data
                END_TOOL_DATA
                """;

        GuardDecisionResult result = service.decide(new GuardDecisionRequest(
                List.of(),
                toolPreface,
                "clarify",
                "req-weather"
        ));

        assertThat(result.policyEvents()).extracting(PolicyEvent::policyType)
                .containsExactly("rag_gate");
    }
}
