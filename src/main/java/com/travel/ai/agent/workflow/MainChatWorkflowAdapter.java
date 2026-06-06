package com.travel.ai.agent.workflow;

import com.travel.ai.agent.guard.GuardDecisionRequest;
import com.travel.ai.agent.guard.GuardDecisionResult;
import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.agent.plan.PlanServiceRequest;
import com.travel.ai.agent.plan.PlanServiceResult;
import com.travel.ai.agent.prompt.PromptAssemblyRequest;
import com.travel.ai.agent.prompt.PromptAssemblyResult;
import com.travel.ai.agent.prompt.PromptAssemblyService;
import com.travel.ai.agent.retrieve.RetrieveRequest;
import com.travel.ai.agent.retrieve.RetrieveResult;
import com.travel.ai.agent.retrieve.RetrieveService;
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.agent.tool.ToolInvocationRequest;
import com.travel.ai.agent.tool.ToolInvocationResult;
import com.travel.ai.agent.tool.ToolInvocationService;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.plan.PlanPhysicalStagePolicy;
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
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.runtime.trace.RuntimeTraceMapper;
import com.travel.ai.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.travel.ai.tools.ToolObservability.log;

/**
 * Pre-WRITE workflow runner for the main chat path.
 *
 * <p>M2 intentionally owns only PLAN/RETRIEVE/TOOL/GUARD orchestration. WRITE,
 * SSE assembly, ChatClient streaming, and Redis memory remain in TravelAgent.</p>
 */
public final class MainChatWorkflowAdapter {

    private static final Logger log = LoggerFactory.getLogger(MainChatWorkflowAdapter.class);

    private final AppAgentProperties appAgentProperties;
    private final LinearWorkflowRuntime linearWorkflowRuntime;
    private final PlanService planService;
    private final RetrieveService retrieveService;
    private final ToolInvocationService toolInvocationService;
    private final GuardDecisionService guardDecisionService;
    private final PromptAssemblyService promptAssemblyService;
    private final BooleanSupplier weatherToolEnabled;
    private final BooleanSupplier marketDataToolEnabled;
    private final IntSupplier weatherSummaryMaxChars;
    private final IntSupplier marketDataSummaryMaxChars;
    private final Supplier<String> emptyHitsBehavior;
    private final int maxContextDocs;
    private final int topKPerQuery;

    public MainChatWorkflowAdapter(AppAgentProperties appAgentProperties,
                                   LinearWorkflowRuntime linearWorkflowRuntime,
                                   PlanService planService,
                                   RetrieveService retrieveService,
                                   ToolInvocationService toolInvocationService,
                                   GuardDecisionService guardDecisionService,
                                   PromptAssemblyService promptAssemblyService,
                                   BooleanSupplier weatherToolEnabled,
                                   BooleanSupplier marketDataToolEnabled,
                                   IntSupplier weatherSummaryMaxChars,
                                   IntSupplier marketDataSummaryMaxChars,
                                   Supplier<String> emptyHitsBehavior,
                                   int maxContextDocs,
                                   int topKPerQuery) {
        this.appAgentProperties = Objects.requireNonNull(appAgentProperties, "appAgentProperties must not be null");
        this.linearWorkflowRuntime = Objects.requireNonNull(linearWorkflowRuntime, "linearWorkflowRuntime must not be null");
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.retrieveService = Objects.requireNonNull(retrieveService, "retrieveService must not be null");
        this.toolInvocationService = Objects.requireNonNull(toolInvocationService, "toolInvocationService must not be null");
        this.guardDecisionService = Objects.requireNonNull(guardDecisionService, "guardDecisionService must not be null");
        this.promptAssemblyService = Objects.requireNonNull(promptAssemblyService, "promptAssemblyService must not be null");
        this.weatherToolEnabled = Objects.requireNonNull(weatherToolEnabled, "weatherToolEnabled must not be null");
        this.marketDataToolEnabled = Objects.requireNonNull(marketDataToolEnabled, "marketDataToolEnabled must not be null");
        this.weatherSummaryMaxChars = Objects.requireNonNull(weatherSummaryMaxChars, "weatherSummaryMaxChars must not be null");
        this.marketDataSummaryMaxChars = Objects.requireNonNull(marketDataSummaryMaxChars, "marketDataSummaryMaxChars must not be null");
        this.emptyHitsBehavior = Objects.requireNonNull(emptyHitsBehavior, "emptyHitsBehavior must not be null");
        this.maxContextDocs = maxContextDocs;
        this.topKPerQuery = topKPerQuery;
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
        PlanStageNode.PhysicalStageFlags flags = stagePlanAndResolvePhysicalStages(ctx);
        ctx.stageEvents.add(StageEvent.end(StageName.PLAN, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.PLAN, 0L)));
        onPhysicalStageFlagsResolved(ctx, flags);

        if (flags.runRetrieve()) {
            ctx.stageEvents.add(StageEvent.start(StageName.RETRIEVE, ctx.requestId));
            stageRetrieve(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.RETRIEVE, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.RETRIEVE, 0L)));
        } else {
            applyRetrieveSkippedState(ctx);
            ctx.stageEvents.add(StageEvent.skip(StageName.RETRIEVE, ctx.requestId, "skipped_by_plan"));
        }
        if (flags.runTool()) {
            ctx.stageEvents.add(StageEvent.start(StageName.TOOL, ctx.requestId));
            stageTool(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.TOOL, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.TOOL, 0L)));
        } else {
            applyToolSkippedState(ctx);
            ctx.stageEvents.add(StageEvent.skip(StageName.TOOL, ctx.requestId, "skipped_by_plan"));
        }
        if (flags.runGuard()) {
            ctx.stageEvents.add(StageEvent.start(StageName.GUARD, ctx.requestId));
            stageGuard(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.GUARD, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.GUARD, 0L)));
        } else {
            onGuardSkippedByPlan(ctx);
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
                        return MainChatWorkflowAdapter.this.stagePlanAndResolvePhysicalStages(ctx);
                    }

                    @Override
                    public void onPhysicalStageFlagsResolved(PlanStageNode.PhysicalStageFlags flags) {
                        MainChatWorkflowAdapter.this.onPhysicalStageFlagsResolved(ctx, flags);
                    }
                }),
                new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
                    @Override
                    public void stageRetrieve() {
                        MainChatWorkflowAdapter.this.stageRetrieve(ctx);
                    }

                    @Override
                    public void onRetrieveSkippedByPlan() {
                        MainChatWorkflowAdapter.this.applyRetrieveSkippedState(ctx);
                    }
                }),
                new ToolStageNode(new ToolStageNode.ToolStageDelegate() {
                    @Override
                    public void stageTool() {
                        MainChatWorkflowAdapter.this.stageTool(ctx);
                    }

                    @Override
                    public void onToolSkippedByPlan() {
                        MainChatWorkflowAdapter.this.applyToolSkippedState(ctx);
                    }
                }),
                new GuardStageNode(new GuardStageNode.GuardStageDelegate() {
                    @Override
                    public void stageGuard() {
                        MainChatWorkflowAdapter.this.stageGuard(ctx);
                    }

                    @Override
                    public void onGuardSkippedByPlan() {
                        MainChatWorkflowAdapter.this.onGuardSkippedByPlan(ctx);
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

    private PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages(WorkflowTurnState ctx) {
        PlanPhysicalStagePolicy.PhysicalStageFlags flags = stagePlan(ctx);
        return new PlanStageNode.PhysicalStageFlags(
                flags.runRetrieve(),
                flags.runTool(),
                flags.runGuard()
        );
    }

    private void onPhysicalStageFlagsResolved(WorkflowTurnState ctx, PlanStageNode.PhysicalStageFlags flags) {
        log.info("[agent] physical_stages retrieve={} tool={} guard={} requestId={}",
                flags.runRetrieve(), flags.runTool(), flags.runGuard(), ctx.requestId);
    }

    private PlanPhysicalStagePolicy.PhysicalStageFlags stagePlan(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] PLAN start requestId={}", ctx.requestId);
        PlanServiceResult result = planService.plan(new PlanServiceRequest(
                ctx.userMessage,
                ctx.requestId,
                appAgentProperties.getPlanStage().isEnabled()
        ));
        ctx.planJson = result.planJson();
        ctx.planDraftSource = result.planDraftSource();
        ctx.planParseOutcome = result.planParseOutcome();
        ctx.planParseAttempts = result.planParseAttempts();
        ctx.planParseResolved = result.planParseResolved();
        logPreWriteStageBoundary(StageName.PLAN, t0, ctx);
        return result.physicalStageFlags();
    }

    private void stageRetrieve(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] RETRIEVE start requestId={}", ctx.requestId);

        ctx.currentUser = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "anonymous";
        RetrieveResult result = retrieveService.retrieve(new RetrieveRequest(
                ctx.userMessage,
                ctx.currentUser,
                ctx.requestId,
                maxContextDocs,
                topKPerQuery
        ));
        ctx.currentUser = result.currentUser();
        ctx.userFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("user_id"),
                new Filter.Value(ctx.currentUser)
        );
        ctx.queries = result.queries();
        ctx.docs = result.docs();
        ctx.promptBase = result.promptBase();
        ctx.citationBlock = result.citationBlock();
        ctx.rewriteMs = result.rewriteMs();
        ctx.retrieveMs = result.retrieveMs();

        logPreWriteStageBoundary(StageName.RETRIEVE, t0, ctx);
    }

    private void stageTool(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] TOOL start requestId={}", ctx.requestId);

        ToolInvocationResult invocation = toolInvocationService.invoke(new ToolInvocationRequest(
                ctx.userMessage,
                ctx.requestId,
                weatherToolEnabled.getAsBoolean(),
                marketDataToolEnabled.getAsBoolean(),
                weatherSummaryMaxChars.getAsInt(),
                marketDataSummaryMaxChars.getAsInt()
        ));
        ctx.toolPreface = invocation.toolPreface();
        if (invocation.toolResult() != null) {
            log(log, invocation.toolResult(), ctx.requestId);
            captureRuntimeToolTrace(ctx, invocation.toolResult());
        }
        ctx.policyEvents.addAll(invocation.policyEvents());

        mergeFinalPromptFromCtx(ctx);

        logPreWriteStageBoundary(StageName.TOOL, t0, ctx);
    }

    private void stageGuard(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] GUARD start requestId={}", ctx.requestId);

        GuardDecisionResult decision = guardDecisionService.decide(new GuardDecisionRequest(
                ctx.docs,
                ctx.toolPreface,
                emptyHitsBehavior.get(),
                ctx.requestId
        ));
        ctx.skipLlmForEmptyHits = decision.skipLlm();
        ctx.emptyHitsClarifyBody = decision.clarifyBody() != null ? decision.clarifyBody() : "";
        ctx.emptyHitsGateLogCode = decision.emptyHitsGateLogCode();
        ctx.policyEvents.addAll(decision.policyEvents());
        switch (decision.reason()) {
            case APPLIED_CLARIFY_RAG_EMPTY, APPLIED_CLARIFY_TOOL_NO_PAYLOAD -> log.info(
                    "[guard] empty_hits gate=clarify error_code={} requestId={}",
                    decision.emptyHitsGateLogCode(), ctx.requestId);
            case SKIPPED_TOOL_SUBSTANTIVE_PAYLOAD -> log.info(
                    "[guard] empty_hits skipped gate tool_data_present requestId={}", ctx.requestId);
            case SKIPPED_HAS_RETRIEVAL_HITS -> log.debug("[guard] retrieve_hits>0 requestId={}", ctx.requestId);
            case SKIPPED_NOT_CLARIFY_MODE -> log.info(
                    "[guard] empty_hits_behavior={} no_clarify_gate requestId={}", emptyHitsBehavior.get(), ctx.requestId);
        }

        logPreWriteStageBoundary(StageName.GUARD, t0, ctx);
    }

    private void applyRetrieveSkippedState(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] RETRIEVE skipped_by_plan requestId={}", ctx.requestId);
        ctx.queries = List.of();
        ctx.rewriteMs = 0;
        ctx.currentUser = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getName()
                : "anonymous";
        ctx.userFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("user_id"),
                new Filter.Value(ctx.currentUser)
        );
        ctx.docs = List.of();
        ctx.retrieveMs = 0;
        ctx.promptBase = ctx.userMessage != null ? ctx.userMessage : "";
        ctx.citationBlock = "【引用片段】\n（本轮未命中知识库）\n\n";
        log.info("检索到 {} 条知识，queries={}", ctx.docs.size(), ctx.queries);
        log.info("[perf] rewrite_ms={} retrieve_ms={} doc_count={} requestId={}",
                ctx.rewriteMs, ctx.retrieveMs, ctx.docs.size(), ctx.requestId);
        logPreWriteStageBoundary(StageName.RETRIEVE, t0, ctx);
    }

    private void applyToolSkippedState(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] TOOL skipped_by_plan requestId={}", ctx.requestId);
        ctx.toolPreface = "";
        mergeFinalPromptFromCtx(ctx);
        logPreWriteStageBoundary(StageName.TOOL, t0, ctx);
    }

    private void onGuardSkippedByPlan(WorkflowTurnState ctx) {
        log.info("[stage] GUARD skipped_by_plan requestId={}", ctx.requestId);
    }

    private void mergeFinalPromptFromCtx(WorkflowTurnState ctx) {
        PromptAssemblyResult result = promptAssemblyService.assemble(new PromptAssemblyRequest(
                ctx.currentUser,
                ctx.toolPreface,
                ctx.planJson,
                ctx.promptBase
        ));
        ctx.finalPromptForLlm = result.finalPromptForLlm();
    }

    private void logPreWriteStageBoundary(StageName stage, long startNs, WorkflowTurnState ctx) {
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        String requestId = ctx != null ? ctx.requestId : "";
        log.info("[stage] {} done elapsed_ms={} requestId={}", stage, ms, requestId);
        if (ctx != null && stage != null) {
            ctx.stageElapsedMs.put(stage, ms);
        }
    }

    public static void captureRuntimeToolTrace(WorkflowTurnState ctx, ToolResult result) {
        if (ctx == null || !ctx.workflowRuntimePath || result == null) {
            return;
        }
        ToolTrace trace = RuntimeTraceMapper.toToolTrace(result);
        if (trace != null) {
            ctx.runtimeToolTraces.add(trace);
        }
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
