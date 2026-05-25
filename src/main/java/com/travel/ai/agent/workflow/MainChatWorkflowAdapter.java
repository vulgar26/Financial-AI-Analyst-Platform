package com.travel.ai.agent.workflow;

import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import com.travel.ai.runtime.node.GuardStageNode;
import com.travel.ai.runtime.node.PlanStageNode;
import com.travel.ai.runtime.node.RetrieveStageNode;
import com.travel.ai.runtime.node.ToolStageNode;
import com.travel.ai.runtime.trace.StageTrace;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pre-WRITE workflow runner for the main chat path.
 *
 * <p>M2 intentionally owns only PLAN/RETRIEVE/TOOL/GUARD orchestration. WRITE,
 * SSE assembly, ChatClient streaming, and Redis memory remain in TravelAgent.</p>
 */
public final class MainChatWorkflowAdapter {

    private final AppAgentProperties appAgentProperties;
    private final LinearWorkflowRuntime linearWorkflowRuntime;
    private final MainChatWorkflowDelegate delegate;

    public MainChatWorkflowAdapter(AppAgentProperties appAgentProperties,
                                   LinearWorkflowRuntime linearWorkflowRuntime,
                                   MainChatWorkflowDelegate delegate) {
        this.appAgentProperties = Objects.requireNonNull(appAgentProperties, "appAgentProperties must not be null");
        this.linearWorkflowRuntime = Objects.requireNonNull(linearWorkflowRuntime, "linearWorkflowRuntime must not be null");
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
    }

    public void runPreWriteWorkflow(WorkflowTurnState ctx) {
        if (shouldUseWorkflowRuntime(appAgentProperties)) {
            runLinearStagesWithRuntime(ctx);
        } else {
            runLinearStages(ctx);
        }
    }

    void runLinearStages(WorkflowTurnState ctx) {
        ctx.stageEvents.add(StageEvent.start(StageName.PLAN, ctx.requestId));
        PlanStageNode.PhysicalStageFlags flags = delegate.stagePlanAndResolvePhysicalStages(ctx);
        ctx.stageEvents.add(StageEvent.end(StageName.PLAN, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.PLAN, 0L)));
        delegate.onPhysicalStageFlagsResolved(ctx, flags);

        if (flags.runRetrieve()) {
            ctx.stageEvents.add(StageEvent.start(StageName.RETRIEVE, ctx.requestId));
            delegate.stageRetrieve(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.RETRIEVE, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.RETRIEVE, 0L)));
        } else {
            delegate.onRetrieveSkippedByPlan(ctx);
            ctx.stageEvents.add(StageEvent.skip(StageName.RETRIEVE, ctx.requestId, "skipped_by_plan"));
        }
        if (flags.runTool()) {
            ctx.stageEvents.add(StageEvent.start(StageName.TOOL, ctx.requestId));
            delegate.stageTool(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.TOOL, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.TOOL, 0L)));
        } else {
            delegate.onToolSkippedByPlan(ctx);
            ctx.stageEvents.add(StageEvent.skip(StageName.TOOL, ctx.requestId, "skipped_by_plan"));
        }
        if (flags.runGuard()) {
            ctx.stageEvents.add(StageEvent.start(StageName.GUARD, ctx.requestId));
            delegate.stageGuard(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.GUARD, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.GUARD, 0L)));
        } else {
            delegate.onGuardSkippedByPlan(ctx);
            ctx.stageEvents.add(StageEvent.skip(StageName.GUARD, ctx.requestId, "skipped_by_plan"));
        }
    }

    void runLinearStagesWithRuntime(WorkflowTurnState ctx) {
        ctx.workflowRuntimePath = true;
        WorkflowTask task = WorkflowTask.of(
                "finance_analyst_chat",
                "v1",
                "finance",
                ctx.requestId,
                ctx.conversationId,
                ctx.userMessage
        );
        WorkflowContext runtimeCtx = linearWorkflowRuntime.run(task, List.of(
                new PlanStageNode(new PlanStageNode.PlanStageDelegate() {
                    @Override
                    public PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages() {
                        return delegate.stagePlanAndResolvePhysicalStages(ctx);
                    }

                    @Override
                    public void onPhysicalStageFlagsResolved(PlanStageNode.PhysicalStageFlags flags) {
                        delegate.onPhysicalStageFlagsResolved(ctx, flags);
                    }
                }),
                new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
                    @Override
                    public void stageRetrieve() {
                        delegate.stageRetrieve(ctx);
                    }

                    @Override
                    public void onRetrieveSkippedByPlan() {
                        delegate.onRetrieveSkippedByPlan(ctx);
                    }
                }),
                new ToolStageNode(new ToolStageNode.ToolStageDelegate() {
                    @Override
                    public void stageTool() {
                        delegate.stageTool(ctx);
                    }

                    @Override
                    public void onToolSkippedByPlan() {
                        delegate.onToolSkippedByPlan(ctx);
                    }
                }),
                new GuardStageNode(new GuardStageNode.GuardStageDelegate() {
                    @Override
                    public void stageGuard() {
                        delegate.stageGuard(ctx);
                    }

                    @Override
                    public void onGuardSkippedByPlan() {
                        delegate.onGuardSkippedByPlan(ctx);
                    }
                })
        ));
        captureRuntimeStageTraces(ctx, runtimeCtx.getStageTraces());
        ctx.stageEvents.addAll(toStageEventsForRuntime(runtimeCtx.getStageTraces(), ctx.requestId));
        StageTrace failed = firstFailedTrace(runtimeCtx.getStageTraces());
        if (failed != null) {
            throw new IllegalStateException("workflow runtime node failed: " + failed.stage()
                    + (failed.message() != null && !failed.message().isBlank() ? " - " + failed.message() : ""));
        }
    }

    public static boolean shouldUseWorkflowRuntime(AppAgentProperties properties) {
        return properties != null && properties.getWorkflowRuntime().isEnabled();
    }

    public static void captureRuntimeStageTraces(WorkflowTurnState ctx, List<StageTrace> traces) {
        if (ctx == null || traces == null || traces.isEmpty()) {
            return;
        }
        ctx.runtimeStageTraces.clear();
        ctx.runtimeStageTraces.addAll(traces);
    }

    public static List<StageEvent> toStageEventsForRuntime(List<StageTrace> traces, String requestId) {
        if (traces == null || traces.isEmpty()) {
            return List.of();
        }
        List<StageEvent> events = new ArrayList<>();
        for (StageTrace trace : traces) {
            StageName stage = parseStageName(trace.stage());
            if (stage == null) {
                continue;
            }
            NodeStatus status = trace.status();
            if (status == NodeStatus.SKIPPED) {
                events.add(StageEvent.skip(stage, requestId, skipReason(trace)));
            } else if (status == NodeStatus.SUCCESS) {
                events.add(StageEvent.start(stage, requestId));
                events.add(StageEvent.end(stage, requestId, trace.elapsedMs() != null ? trace.elapsedMs() : 0L));
            } else {
                events.add(StageEvent.start(stage, requestId));
                events.add(new StageEvent(
                        StageEventKind.ERROR,
                        stage,
                        requestId,
                        trace.elapsedMs(),
                        trace.errorCode(),
                        trace.message(),
                        trace.attrs() != null ? trace.attrs() : Map.of()
                ));
            }
        }
        return events;
    }

    private static StageTrace firstFailedTrace(List<StageTrace> traces) {
        if (traces == null) {
            return null;
        }
        for (StageTrace trace : traces) {
            if (trace != null && (trace.status() == NodeStatus.FAILED || trace.status() == NodeStatus.TIMEOUT)) {
                return trace;
            }
        }
        return null;
    }

    private static StageName parseStageName(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        try {
            return StageName.valueOf(stage);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String skipReason(StageTrace trace) {
        if (trace != null && trace.attrs() != null) {
            String reason = trace.attrs().get("reason");
            if (reason != null && !reason.isBlank()) {
                return reason;
            }
        }
        return "skipped_by_plan";
    }
}
