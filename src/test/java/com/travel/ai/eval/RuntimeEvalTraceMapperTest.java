package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalChatStageTrace;
import com.travel.ai.eval.dto.EvalChatToolTrace;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEvalTraceMapperTest {

    @Test
    void stageTraceMapper_mapsSuccess() {
        StageTrace trace = new StageTrace("PLAN", NodeStatus.SUCCESS, 12L, null, null, Map.of());

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("PLAN");
        assertThat(dto.getKind()).isEqualTo("workflow_node");
        assertThat(dto.getStatus()).isEqualTo("success");
        assertThat(dto.getElapsedMs()).isEqualTo(12L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getAttrs()).isNull();
    }

    @Test
    void stageTraceMapper_mapsSkippedWithReason() {
        StageTrace trace = new StageTrace(
                "TOOL",
                NodeStatus.SKIPPED,
                0L,
                null,
                "skipped_by_plan",
                Map.of("reason", "skipped_by_plan")
        );

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("TOOL");
        assertThat(dto.getKind()).isEqualTo("workflow_node");
        assertThat(dto.getStatus()).isEqualTo("skipped");
        assertThat(dto.getElapsedMs()).isEqualTo(0L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getAttrs()).containsEntry("reason", "skipped_by_plan");
    }

    @Test
    void stageTraceMapper_mapsFailedWithErrorCode() {
        StageTrace trace = new StageTrace(
                "RETRIEVE",
                NodeStatus.FAILED,
                8L,
                "VECTOR_STORE_ERROR",
                "failed",
                Map.of("exception", "IllegalStateException")
        );

        EvalChatStageTrace dto = RuntimeEvalTraceMapper.toEvalStageTrace(trace);

        assertThat(dto.getStage()).isEqualTo("RETRIEVE");
        assertThat(dto.getStatus()).isEqualTo("failed");
        assertThat(dto.getElapsedMs()).isEqualTo(8L);
        assertThat(dto.getErrorCode()).isEqualTo("VECTOR_STORE_ERROR");
        assertThat(dto.getAttrs()).containsEntry("exception", "IllegalStateException");
    }

    @Test
    void toolTraceMapper_mapsBasicFields() {
        ToolTrace trace = new ToolTrace(
                "market_data",
                "market_data",
                true,
                true,
                true,
                "ok",
                21L,
                null,
                "input:1",
                "output:1",
                Map.of("mock_mode", "true")
        );

        EvalChatToolTrace dto = RuntimeEvalTraceMapper.toEvalToolTrace(trace);

        assertThat(dto.getToolName()).isEqualTo("market_data");
        assertThat(dto.getConnector()).isEqualTo("market_data");
        assertThat(dto.getRequired()).isTrue();
        assertThat(dto.getUsed()).isTrue();
        assertThat(dto.getSucceeded()).isTrue();
        assertThat(dto.getOutcome()).isEqualTo("ok");
        assertThat(dto.getLatencyMs()).isEqualTo(21L);
        assertThat(dto.getErrorCode()).isNull();
        assertThat(dto.getInputRef()).isEqualTo("input:1");
        assertThat(dto.getOutputRef()).isEqualTo("output:1");
        assertThat(dto.getAttrs()).containsEntry("mock_mode", "true");
    }

    @Test
    void toolTraceMapper_omitsOrKeepsEmptyAttrsConsistently() {
        ToolTrace emptyAttrs = new ToolTrace(
                "weather",
                "weather",
                true,
                false,
                false,
                "disabled_by_policy",
                0L,
                "TOOL_POLICY_DISABLED",
                null,
                null,
                Map.of()
        );

        EvalChatToolTrace dto = RuntimeEvalTraceMapper.toEvalToolTrace(emptyAttrs);

        assertThat(dto.getToolName()).isEqualTo("weather");
        assertThat(dto.getOutcome()).isEqualTo("disabled_by_policy");
        assertThat(dto.getAttrs()).isNull();
    }
}
