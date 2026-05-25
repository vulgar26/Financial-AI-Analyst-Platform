package com.travel.ai.agent.workflow;

import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.node.PlanStageNode;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainChatWorkflowAdapterTest {

    @Test
    void flagOff_runsLegacyStagesInSameOrder() {
        WorkflowTurnState ctx = state();
        MainChatWorkflowAdapter adapter = adapter(false, new RecordingDelegate(true, true, true));

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
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
        assertThat(ctx.workflowRuntimePath).isFalse();
        assertThat(ctx.runtimeStageTraces).isEmpty();
    }

    @Test
    void flagOn_runsRuntimeStagesInSameOrder() {
        WorkflowTurnState ctx = state();
        MainChatWorkflowAdapter adapter = adapter(true, new RecordingDelegate(true, true, true));

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
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
        assertThat(ctx.workflowRuntimePath).isTrue();
    }

    @Test
    void flagOn_capturesRuntimeStageTraces() {
        WorkflowTurnState ctx = state();
        MainChatWorkflowAdapter adapter = adapter(true, new RecordingDelegate(true, false, true));

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE", "TOOL", "GUARD");
    }

    @Test
    void flagOn_convertsFailedTraceToException() {
        WorkflowTurnState ctx = state();
        RecordingDelegate delegate = new RecordingDelegate(true, true, true);
        delegate.failTool = true;
        MainChatWorkflowAdapter adapter = adapter(true, delegate);

        assertThatThrownBy(() -> adapter.runPreWriteWorkflow(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow runtime node failed: TOOL");

        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::stage)
                .contains("TOOL");
    }

    @Test
    void flagOn_doesNotDuplicateStageEvents() {
        WorkflowTurnState ctx = state();
        MainChatWorkflowAdapter adapter = adapter(true, new RecordingDelegate(false, false, false));

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly("PLAN:START", "PLAN:END", "RETRIEVE:SKIP", "TOOL:SKIP", "GUARD:SKIP");
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.RETRIEVE).count()).isEqualTo(1);
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.TOOL).count()).isEqualTo(1);
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.GUARD).count()).isEqualTo(1);
        assertThat(ctx.stageEvents)
                .filteredOn(e -> e.kind() == StageEventKind.SKIP)
                .extracting(e -> e.attrs().get("reason"))
                .containsOnly("skipped_by_plan");
    }

    private static MainChatWorkflowAdapter adapter(boolean runtimeEnabled, RecordingDelegate delegate) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(runtimeEnabled);
        return new MainChatWorkflowAdapter(properties, new LinearWorkflowRuntime(), delegate);
    }

    private static WorkflowTurnState state() {
        return new WorkflowTurnState("conv-1", "question", "req-1");
    }

    private static final class RecordingDelegate implements MainChatWorkflowDelegate {
        private final boolean runRetrieve;
        private final boolean runTool;
        private final boolean runGuard;
        private boolean failTool;
        private final List<String> calls = new ArrayList<>();

        private RecordingDelegate(boolean runRetrieve, boolean runTool, boolean runGuard) {
            this.runRetrieve = runRetrieve;
            this.runTool = runTool;
            this.runGuard = runGuard;
        }

        @Override
        public PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages(WorkflowTurnState ctx) {
            calls.add("PLAN");
            ctx.stageElapsedMs.put(StageName.PLAN, 1L);
            return new PlanStageNode.PhysicalStageFlags(runRetrieve, runTool, runGuard);
        }

        @Override
        public void onPhysicalStageFlagsResolved(WorkflowTurnState ctx, PlanStageNode.PhysicalStageFlags flags) {
            calls.add("FLAGS");
        }

        @Override
        public void stageRetrieve(WorkflowTurnState ctx) {
            calls.add("RETRIEVE");
            ctx.stageElapsedMs.put(StageName.RETRIEVE, 2L);
        }

        @Override
        public void onRetrieveSkippedByPlan(WorkflowTurnState ctx) {
            calls.add("RETRIEVE_SKIP");
            ctx.stageElapsedMs.put(StageName.RETRIEVE, 0L);
        }

        @Override
        public void stageTool(WorkflowTurnState ctx) {
            calls.add("TOOL");
            if (failTool) {
                throw new IllegalStateException("tool failed");
            }
            ctx.stageElapsedMs.put(StageName.TOOL, 3L);
        }

        @Override
        public void onToolSkippedByPlan(WorkflowTurnState ctx) {
            calls.add("TOOL_SKIP");
            ctx.stageElapsedMs.put(StageName.TOOL, 0L);
        }

        @Override
        public void stageGuard(WorkflowTurnState ctx) {
            calls.add("GUARD");
            ctx.stageElapsedMs.put(StageName.GUARD, 4L);
        }

        @Override
        public void onGuardSkippedByPlan(WorkflowTurnState ctx) {
            calls.add("GUARD_SKIP");
        }
    }
}
