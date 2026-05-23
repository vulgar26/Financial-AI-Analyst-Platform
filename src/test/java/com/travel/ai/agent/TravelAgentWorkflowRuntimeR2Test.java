package com.travel.ai.agent;

import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.tools.ToolOutcome;
import com.travel.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TravelAgentWorkflowRuntimeR2Test {

    @Test
    void featureFlagOff_usesLegacyPath() {
        AppAgentProperties properties = new AppAgentProperties();

        assertThat(properties.getWorkflowRuntime().isEnabled()).isFalse();
        assertThat(TravelAgent.shouldUseWorkflowRuntime(properties)).isFalse();
    }

    @Test
    void featureFlagOn_runtimePathEmitsSameStageOrder() {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(true);

        List<StageEvent> events = TravelAgent.toStageEventsForRuntime(List.of(
                success("PLAN"),
                success("RETRIEVE"),
                success("TOOL"),
                success("GUARD")
        ), "req-1");

        assertThat(TravelAgent.shouldUseWorkflowRuntime(properties)).isTrue();
        assertThat(events)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START",
                        "PLAN:END",
                        "RETRIEVE:START",
                        "RETRIEVE:END",
                        "TOOL:START",
                        "TOOL:END",
                        "GUARD:START",
                        "GUARD:END"
                );
        assertThat(events)
                .extracting(StageEvent::requestId)
                .containsOnly("req-1");
    }

    @Test
    void featureFlagOn_marketDataPolicyNotDuplicated() {
        List<StageEvent> events = TravelAgent.toStageEventsForRuntime(List.of(
                success("TOOL"),
                success("GUARD")
        ), "req-market");

        assertThat(events).hasSize(4);
        assertThat(events).allSatisfy(event -> assertThat(event.kind())
                .isIn(StageEventKind.START, StageEventKind.END));
        assertThat(events)
                .extracting(StageEvent::stage)
                .containsExactly(StageName.TOOL, StageName.TOOL, StageName.GUARD, StageName.GUARD);
    }

    @Test
    void featureFlagOn_retrieveOrToolSkipDoesNotDuplicateStageEvent() {
        List<StageEvent> events = TravelAgent.toStageEventsForRuntime(List.of(
                skipped("RETRIEVE"),
                skipped("TOOL")
        ), "req-skip");

        assertThat(events)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly("RETRIEVE:SKIP", "TOOL:SKIP");
        assertThat(events)
                .extracting(e -> e.attrs().get("reason"))
                .containsExactly("skipped_by_plan", "skipped_by_plan");
    }

    @Test
    void runtimeFlagOn_capturesStageTracesInternally() {
        TravelAgent.MainAgentTurnContext ctx = new TravelAgent.MainAgentTurnContext(
                "conv-stage",
                "question",
                "req-stage"
        );
        List<StageTrace> traces = List.of(
                success("PLAN"),
                success("RETRIEVE"),
                skipped("TOOL"),
                success("GUARD")
        );

        TravelAgent.captureRuntimeStageTraces(ctx, traces);

        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE", "TOOL", "GUARD");
        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::status)
                .containsExactly(NodeStatus.SUCCESS, NodeStatus.SUCCESS, NodeStatus.SKIPPED, NodeStatus.SUCCESS);
    }

    @Test
    void runtimeFlagOn_capturesMarketDataToolTraceInternally() {
        TravelAgent.MainAgentTurnContext ctx = new TravelAgent.MainAgentTurnContext(
                "conv-tool",
                "Explain AAPL price and P/E",
                "req-tool"
        );
        ctx.workflowRuntimePath = true;
        ToolResult result = new ToolResult(
                "market_data",
                true,
                true,
                true,
                ToolOutcome.OK,
                null,
                17L,
                "mock market data",
                false
        );

        TravelAgent.captureRuntimeToolTrace(ctx, result);

        assertThat(ctx.runtimeToolTraces).hasSize(1);
        ToolTrace trace = ctx.runtimeToolTraces.get(0);
        assertThat(trace.toolName()).isEqualTo("market_data");
        assertThat(trace.connector()).isEqualTo("market_data");
        assertThat(trace.outcome()).isEqualTo("ok");
        assertThat(trace.attrs()).containsEntry("mock_mode", "true")
                .containsEntry("freshness", "mock_non_realtime")
                .containsEntry("tradable", "false");
    }

    @Test
    void runtimeFlagOff_doesNotCaptureRuntimeTraces() {
        TravelAgent.MainAgentTurnContext ctx = new TravelAgent.MainAgentTurnContext(
                "conv-off",
                "question",
                "req-off"
        );
        ToolResult result = new ToolResult(
                "market_data",
                true,
                true,
                true,
                ToolOutcome.OK,
                null,
                17L,
                "mock market data",
                false
        );

        TravelAgent.captureRuntimeToolTrace(ctx, result);

        assertThat(ctx.workflowRuntimePath).isFalse();
        assertThat(ctx.runtimeStageTraces).isEmpty();
        assertThat(ctx.runtimeToolTraces).isEmpty();
    }

    private static StageTrace success(String stage) {
        return new StageTrace(stage, NodeStatus.SUCCESS, 12L, null, null, Map.of());
    }

    private static StageTrace skipped(String stage) {
        return new StageTrace(
                stage,
                NodeStatus.SKIPPED,
                0L,
                null,
                "skipped_by_plan",
                Map.of("reason", "skipped_by_plan")
        );
    }
}
