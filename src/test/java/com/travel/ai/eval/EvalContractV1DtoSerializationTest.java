package com.travel.ai.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.eval.dto.EvalChatEvidenceSummary;
import com.travel.ai.eval.dto.EvalChatMeta;
import com.travel.ai.eval.dto.EvalChatPolicyEvent;
import com.travel.ai.eval.dto.EvalChatStageTrace;
import com.travel.ai.eval.dto.EvalChatToolTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvalContractV1DtoSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void evalContractV1FieldsSerializeAsSnakeCaseAndOmitEmptyCollections() throws Exception {
        EvalChatPolicyEvent policy = new EvalChatPolicyEvent();
        policy.setPolicyType("finance_guard");
        policy.setStage("guard");
        policy.setBehavior("allow");
        policy.setDecision("allow");
        policy.setSeverity("info");
        policy.setRuleId("market_data_mock_disclosure");
        policy.setAttrs(Map.of("workflow_id", "market_data_explain"));

        EvalChatStageTrace stageTrace = new EvalChatStageTrace();
        stageTrace.setStage("TOOL");
        stageTrace.setKind("end");
        stageTrace.setStatus("ok");
        stageTrace.setElapsedMs(12L);
        stageTrace.setAttrs(Map.of("physical_stage", "true"));

        EvalChatToolTrace toolTrace = new EvalChatToolTrace();
        toolTrace.setToolName("market_data");
        toolTrace.setConnector("market_data");
        toolTrace.setRequired(true);
        toolTrace.setUsed(true);
        toolTrace.setSucceeded(true);
        toolTrace.setOutcome("ok");
        toolTrace.setLatencyMs(8L);
        toolTrace.setInputRef("hash:input");
        toolTrace.setOutputRef("hash:output");
        toolTrace.setAttrs(Map.of("mock_mode", "true"));

        EvalChatEvidenceSummary evidenceSummary = new EvalChatEvidenceSummary();
        evidenceSummary.setRetrievalHitCount(3);
        evidenceSummary.setSourceCount(2);
        evidenceSummary.setLowConfidence(false);
        evidenceSummary.setCitationMembershipChecked(true);
        evidenceSummary.setCanonicalHitIdScheme("kb_chunk_id");
        evidenceSummary.setContextTruncated(true);
        evidenceSummary.setContextTruncationReasons(List.of("sources_snippet_truncated"));

        EvalChatMeta meta = new EvalChatMeta("AGENT", "req-1");
        meta.setContractVersion("eval_contract_v1");
        meta.setWorkflowId("market_data_explain");
        meta.setWorkflowVersion("v1");
        meta.setWorkflowFamily("finance");
        meta.setPolicyEvents(List.of(policy));
        meta.setStageTrace(List.of(stageTrace));
        meta.setToolTrace(List.of(toolTrace));
        meta.setEvidenceSummary(evidenceSummary);

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(meta));

        assertEquals("eval_contract_v1", json.get("contract_version").asText());
        assertEquals("market_data_explain", json.get("workflow_id").asText());
        assertEquals("v1", json.get("workflow_version").asText());
        assertEquals("finance", json.get("workflow_family").asText());

        JsonNode event = json.get("policy_events").get(0);
        assertEquals("finance_guard", event.get("policy_type").asText());
        assertEquals("allow", event.get("decision").asText());
        assertEquals("info", event.get("severity").asText());
        assertEquals("market_data_explain", event.get("attrs").get("workflow_id").asText());

        JsonNode stage = json.get("stage_trace").get(0);
        assertEquals("TOOL", stage.get("stage").asText());
        assertEquals("end", stage.get("kind").asText());
        assertEquals("ok", stage.get("status").asText());
        assertEquals(12L, stage.get("elapsed_ms").asLong());
        assertEquals("true", stage.get("attrs").get("physical_stage").asText());

        JsonNode tool = json.get("tool_trace").get(0);
        assertEquals("market_data", tool.get("tool_name").asText());
        assertEquals("market_data", tool.get("connector").asText());
        assertTrue(tool.get("required").asBoolean());
        assertTrue(tool.get("used").asBoolean());
        assertTrue(tool.get("succeeded").asBoolean());
        assertEquals("ok", tool.get("outcome").asText());
        assertEquals(8L, tool.get("latency_ms").asLong());
        assertEquals("hash:input", tool.get("input_ref").asText());
        assertEquals("hash:output", tool.get("output_ref").asText());
        assertEquals("true", tool.get("attrs").get("mock_mode").asText());

        JsonNode evidence = json.get("evidence_summary");
        assertEquals(3, evidence.get("retrieval_hit_count").asInt());
        assertEquals(2, evidence.get("source_count").asInt());
        assertFalse(evidence.get("low_confidence").asBoolean());
        assertTrue(evidence.get("citation_membership_checked").asBoolean());
        assertEquals("kb_chunk_id", evidence.get("canonical_hit_id_scheme").asText());
        assertTrue(evidence.get("context_truncated").asBoolean());
        assertEquals("sources_snippet_truncated", evidence.get("context_truncation_reasons").get(0).asText());
        assertFalse(evidence.has("low_confidence_reasons"));
    }
}
