package com.travel.ai.agent;

import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import com.travel.ai.runtime.node.GuardStageNode;
import com.travel.ai.runtime.node.PlanStageNode;
import com.travel.ai.runtime.node.RetrieveStageNode;
import com.travel.ai.runtime.node.ToolStageNode;
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.tools.ToolOutcome;
import com.travel.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        WorkflowTurnState ctx = new WorkflowTurnState(
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
    void runtimeFlagOn_planStageNodeExecutesDelegateAndWritesRunFlags() {
        WorkflowContext ctx = workflowContext();
        AtomicInteger planCalls = new AtomicInteger();
        AtomicInteger resolvedCalls = new AtomicInteger();
        PlanStageNode node = new PlanStageNode(new PlanStageNode.PlanStageDelegate() {
            @Override
            public PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages() {
                planCalls.incrementAndGet();
                return new PlanStageNode.PhysicalStageFlags(true, true, false);
            }

            @Override
            public void onPhysicalStageFlagsResolved(PlanStageNode.PhysicalStageFlags flags) {
                resolvedCalls.incrementAndGet();
                assertThat(flags.runRetrieve()).isTrue();
                assertThat(flags.runTool()).isTrue();
                assertThat(flags.runGuard()).isFalse();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(node.name()).isEqualTo("PLAN");
        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(planCalls).hasValue(1);
        assertThat(resolvedCalls).hasValue(1);
        assertThat(ctx.getAttrs()).containsEntry("run_retrieve", "true")
                .containsEntry("run_tool", "true")
                .containsEntry("run_guard", "false");
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_planStageNodePropagatesMarketDataPhysicalFlags() {
        WorkflowContext ctx = workflowContext();
        PlanStageNode node = new PlanStageNode(new PlanStageNode.PlanStageDelegate() {
            @Override
            public PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages() {
                return new PlanStageNode.PhysicalStageFlags(false, true, true);
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(ctx.getAttrs()).containsEntry("run_retrieve", "false")
                .containsEntry("run_tool", "true")
                .containsEntry("run_guard", "true");
    }

    @Test
    void runtimeFlagOn_guardStageNodeExecutesDelegateOnce() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_guard", "true");
        AtomicInteger guardCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        GuardStageNode node = new GuardStageNode(new GuardStageNode.GuardStageDelegate() {
            @Override
            public void stageGuard() {
                guardCalls.incrementAndGet();
            }

            @Override
            public void onGuardSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(node.name()).isEqualTo("GUARD");
        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(guardCalls).hasValue(1);
        assertThat(skipCalls).hasValue(0);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_guardStageNodeSkipDoesNotGenerateEventsOrPolicies() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_guard", "false");
        AtomicInteger guardCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        GuardStageNode node = new GuardStageNode(new GuardStageNode.GuardStageDelegate() {
            @Override
            public void stageGuard() {
                guardCalls.incrementAndGet();
            }

            @Override
            public void onGuardSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.attrs()).containsEntry("reason", "skipped_by_plan");
        assertThat(guardCalls).hasValue(0);
        assertThat(skipCalls).hasValue(1);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_retrieveStageNodeExecutesDelegateOnce() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_retrieve", "true");
        AtomicInteger retrieveCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        RetrieveStageNode node = new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
            @Override
            public void stageRetrieve() {
                retrieveCalls.incrementAndGet();
            }

            @Override
            public void onRetrieveSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(node.name()).isEqualTo("RETRIEVE");
        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(retrieveCalls).hasValue(1);
        assertThat(skipCalls).hasValue(0);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_retrieveStageNodeSkipDoesNotGenerateEventsOrPolicies() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_retrieve", "false");
        AtomicInteger retrieveCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        RetrieveStageNode node = new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
            @Override
            public void stageRetrieve() {
                retrieveCalls.incrementAndGet();
            }

            @Override
            public void onRetrieveSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.attrs()).containsEntry("reason", "skipped_by_plan");
        assertThat(retrieveCalls).hasValue(0);
        assertThat(skipCalls).hasValue(1);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_retrieveStageNodeDoesNotOwnRagGatePolicy() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_retrieve", "true");
        RetrieveStageNode node = new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
            @Override
            public void stageRetrieve() {
            }

            @Override
            public void onRetrieveSkippedByPlan() {
            }
        });

        node.execute(ctx);

        assertThat(ctx.getPolicyEvents()).isEmpty();
    }

    @Test
    void runtimeFlagOn_toolStageNodeExecutesDelegateOnce() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_tool", "true");
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        ToolStageNode node = new ToolStageNode(new ToolStageNode.ToolStageDelegate() {
            @Override
            public void stageTool() {
                toolCalls.incrementAndGet();
            }

            @Override
            public void onToolSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(node.name()).isEqualTo("TOOL");
        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(toolCalls).hasValue(1);
        assertThat(skipCalls).hasValue(0);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
        assertThat(ctx.getToolTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_toolStageNodeSkipDoesNotGenerateEventsPoliciesOrToolTrace() {
        WorkflowContext ctx = workflowContext();
        ctx.putAttr("run_tool", "false");
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        ToolStageNode node = new ToolStageNode(new ToolStageNode.ToolStageDelegate() {
            @Override
            public void stageTool() {
                toolCalls.incrementAndGet();
            }

            @Override
            public void onToolSkippedByPlan() {
                skipCalls.incrementAndGet();
            }
        });

        NodeResult result = node.execute(ctx);

        assertThat(result.status()).isEqualTo(NodeStatus.SKIPPED);
        assertThat(result.attrs()).containsEntry("reason", "skipped_by_plan");
        assertThat(toolCalls).hasValue(0);
        assertThat(skipCalls).hasValue(1);
        assertThat(ctx.getPolicyEvents()).isEmpty();
        assertThat(ctx.getStageTraces()).isEmpty();
        assertThat(ctx.getToolTraces()).isEmpty();
    }

    @Test
    void runtimeFlagOn_capturesMarketDataToolTraceInternally() {
        WorkflowTurnState ctx = new WorkflowTurnState(
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
        WorkflowTurnState ctx = new WorkflowTurnState(
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

    private static WorkflowContext workflowContext() {
        return new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat",
                "v1",
                "finance",
                "req-guard",
                "conv-guard",
                "question"
        ));
    }
}
