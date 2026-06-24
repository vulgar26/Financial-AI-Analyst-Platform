package com.travel.ai.agent.workflow;

import com.travel.ai.agent.guard.GuardDecisionRequest;
import com.travel.ai.agent.guard.GuardDecisionResult;
import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.guard.RetrieveEmptyHitGate;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.agent.plan.PlanServiceRequest;
import com.travel.ai.agent.plan.PlanServiceResult;
import com.travel.ai.agent.prompt.AnalysisPackage;
import com.travel.ai.agent.prompt.PromptAssemblyRequest;
import com.travel.ai.agent.prompt.PromptAssemblyResult;
import com.travel.ai.agent.prompt.PromptAssemblyService;
import com.travel.ai.agent.retrieve.EvidencePackage;
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
import com.travel.ai.runtime.node.AnalystAgentDelegate;
import com.travel.ai.runtime.node.AnalystAgentNode;
import com.travel.ai.runtime.node.GuardStageNode;
import com.travel.ai.runtime.node.KnowledgeAgentDelegate;
import com.travel.ai.runtime.node.KnowledgeAgentNode;
import com.travel.ai.runtime.node.PlanStageNode;
import com.travel.ai.runtime.node.RetrieveStageNode;
import com.travel.ai.runtime.node.ToolStageNode;
import com.travel.ai.runtime.node.WorkflowNode;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.runtime.trace.RuntimeTraceMapper;
import com.travel.ai.tools.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

import static com.travel.ai.tools.ToolObservability.log;

/**
 * Pre-WRITE workflow runner for the main chat path.
 *
 * <p>M2 intentionally owns only PLAN/RETRIEVE/TOOL/GUARD orchestration. WRITE,
 * SSE assembly, ChatClient streaming, and Redis memory remain in FinancialAnalystAgentImpl.</p>
 */
public final class MainChatWorkflowAdapter {

    private static final Logger log = LoggerFactory.getLogger(MainChatWorkflowAdapter.class);

    /** 业务语义上限：一次降阈值探测足以判别真缺失/假零命中。引擎侧 maxRedirects 另作机械兜底。 */
    private static final int MAX_RETRIEVAL_REPLANS = 1;

    /** 第二刀相关性 replan 时，附加到用户问题前的改写提示，促使 query rewriter 换角度产出检索串。 */
    private static final String REWRITE_HINT_PREFIX = "（上一次检索到的资料与问题不相关，请换一种角度、用不同的关键词重新表述这个问题）";

    private final AppAgentProperties appAgentProperties;
    private final LinearWorkflowRuntime linearWorkflowRuntime;
    private final PlanService planService;
    private final RetrieveService retrieveService;
    private final ToolInvocationService toolInvocationService;
    private final GuardDecisionService guardDecisionService;
    private final RetrievalRelevanceJudge relevanceJudge;
    private final PromptAssemblyService promptAssemblyService;
    private final BooleanSupplier marketDataToolEnabled;
    private final IntSupplier marketDataSummaryMaxChars;
    private final Supplier<String> emptyHitsBehavior;
    private final int maxContextDocs;
    private final int topKPerQuery;
    private final double similarityThreshold;

    public MainChatWorkflowAdapter(AppAgentProperties appAgentProperties,
                                   LinearWorkflowRuntime linearWorkflowRuntime,
                                   PlanService planService,
                                   RetrieveService retrieveService,
                                   ToolInvocationService toolInvocationService,
                                   GuardDecisionService guardDecisionService,
                                   RetrievalRelevanceJudge relevanceJudge,
                                   PromptAssemblyService promptAssemblyService,
                                   BooleanSupplier marketDataToolEnabled,
                                   IntSupplier marketDataSummaryMaxChars,
                                   Supplier<String> emptyHitsBehavior,
                                   int maxContextDocs,
                                   int topKPerQuery,
                                   double similarityThreshold) {
        this.appAgentProperties = Objects.requireNonNull(appAgentProperties, "appAgentProperties must not be null");
        this.linearWorkflowRuntime = Objects.requireNonNull(linearWorkflowRuntime, "linearWorkflowRuntime must not be null");
        this.planService = Objects.requireNonNull(planService, "planService must not be null");
        this.retrieveService = Objects.requireNonNull(retrieveService, "retrieveService must not be null");
        this.toolInvocationService = Objects.requireNonNull(toolInvocationService, "toolInvocationService must not be null");
        this.guardDecisionService = Objects.requireNonNull(guardDecisionService, "guardDecisionService must not be null");
        this.relevanceJudge = Objects.requireNonNull(relevanceJudge, "relevanceJudge must not be null");
        this.promptAssemblyService = Objects.requireNonNull(promptAssemblyService, "promptAssemblyService must not be null");
        this.marketDataToolEnabled = Objects.requireNonNull(marketDataToolEnabled, "marketDataToolEnabled must not be null");
        this.marketDataSummaryMaxChars = Objects.requireNonNull(marketDataSummaryMaxChars, "marketDataSummaryMaxChars must not be null");
        this.emptyHitsBehavior = Objects.requireNonNull(emptyHitsBehavior, "emptyHitsBehavior must not be null");
        this.maxContextDocs = maxContextDocs;
        this.topKPerQuery = topKPerQuery;
        this.similarityThreshold = similarityThreshold;
    }

    public void runPreWriteWorkflow(WorkflowTurnState ctx) {
        if (shouldUseMultiAgent(appAgentProperties)) {
            runMultiAgentWorkflow(ctx);
        } else if (shouldUseWorkflowRuntime(appAgentProperties)) {
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
                    public boolean stageGuard() {
                        return MainChatWorkflowAdapter.this.stageGuard(ctx);
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

    public static boolean shouldUseMultiAgent(AppAgentProperties properties) {
        return properties != null && properties.getMultiAgent().isEnabled();
    }

    /**
     * 第三条路径：外层图编排。PLAN 先在图外当路由器跑定物理阶段，再据此组装
     * {@code [KnowledgeAgentNode?, AnalystAgentNode]} 交给外层 runtime。两个 Agent 各有自治子图，
     * 之间只穿 {@link EvidencePackage} 资料袋（{@code holder} 物理隔离）；产物 {@link AnalysisPackage}
     * 经 lift 落外层产物槽后解包回 ctx。
     *
     * <p><strong>为何 PLAN 在图外</strong>：要按 PLAN 决定「这一轮到底激活不激活 Knowledge」。
     * 若不检索却仍激活 Knowledge，其空 docs 路径会触发子图内部自我 replan = 会说谎的绿；
     * 故跳过检索时直接套用 {@link #applyRetrieveSkippedState} + 空袋，不让 Knowledge 上场。</p>
     *
     * <p><strong>replan 归属（甲方案）</strong>：零命中/相关性重查整个归 Knowledge 的 JudgeSubNode（带回边）；
     * 这里 Analyst 的 guard 闭包是纯澄清、无 replan。重查归检索质量，澄清归回答策略。</p>
     *
     * <p><strong>可观测</strong>：外层 trace 名是 KNOWLEDGE/ANALYST，映射不到 StageName，子图 trace 也不 lift，
     * 故 {@link #toStageEventsForRuntime} 用不上；本方法自行合成 PLAN/RETRIEVE/TOOL/GUARD 的
     * stageEvents（与 inline {@link #runLinearStages} 的 START/END/SKIP 形状对齐），保证与现有路径一致。</p>
     */
    void runMultiAgentWorkflow(WorkflowTurnState ctx) {
        ctx.workflowRuntimePath = true;

        // ① PLAN 路由器：图外先跑，定物理阶段。stageEvents 与 inline 对齐：PLAN START→END。
        ctx.stageEvents.add(StageEvent.start(StageName.PLAN, ctx.requestId));
        PlanStageNode.PhysicalStageFlags flags = stagePlanAndResolvePhysicalStages(ctx);
        ctx.stageEvents.add(StageEvent.end(StageName.PLAN, ctx.requestId,
                ctx.stageElapsedMs.getOrDefault(StageName.PLAN, 0L)));
        onPhysicalStageFlagsResolved(ctx, flags);

        // ② 资料袋桥：Knowledge 写入、Analyst 通过 holder::get 读取（物理够不着子图内部状态）。
        AtomicReference<EvidencePackage> holder = new AtomicReference<>();

        // ③ 据 PLAN 决定要不要激活 Knowledge。
        List<WorkflowNode> agents = new ArrayList<>();
        if (flags.runRetrieve()) {
            agents.add(KnowledgeAgentNode.of(buildKnowledgeDelegate(ctx, holder)));
        } else {
            // 不检索：直接套用跳过态 + 空袋，不让 Knowledge 上场（避免其空路径自我 replan 说谎）。
            applyRetrieveSkippedState(ctx);
            holder.set(new EvidencePackage(ctx.currentUser, List.of(), List.of(),
                    ctx.promptBase, ctx.citationBlock, 0L));
            ctx.stageEvents.add(StageEvent.skip(StageName.RETRIEVE, ctx.requestId, "skipped_by_plan"));
        }
        agents.add(AnalystAgentNode.of(buildAnalystDelegate(ctx, flags), holder::get));

        // ④ 跑外层 runtime（自治子图调度、回边由引擎管）。
        WorkflowTask task = WorkflowTask.of(
                "finance_analyst_chat",
                "v1",
                "finance",
                ctx.requestId,
                ctx.conversationId,
                ctx.userMessage
        );
        WorkflowContext runtimeCtx = linearWorkflowRuntime.run(task, agents);

        // ⑤ 解包 lift 上来的 AnalysisPackage 回 ctx。
        AnalysisPackage analysis = runtimeCtx.getProduct(AnalystAgentNode.PRODUCT_ANALYSIS, AnalysisPackage.class);
        if (analysis != null) {
            ctx.finalPromptForLlm = analysis.finalPromptForLlm();
            ctx.skipLlmForEmptyHits = analysis.skipLlm();
            ctx.emptyHitsClarifyBody = analysis.clarifyBody();
        }

        // ⑥ 记账：把外层 runtime 的 trace（含子图复制来的 agent=/scope= 标签）落进 ctx，
        // 让 eval 报告能按 Agent 归因。与 runtime 路径同样在失败抛出前 capture，失败链路也留痕。
        // 注意只 capture、不走 toStageEventsForRuntime：SSE stageEvents 已由本方法 ②/③/Knowledge/Analyst
        // 闭包按 PLAN/RETRIEVE/TOOL/GUARD 形状自行合成（外层 trace 名是 KNOWLEDGE/ANALYST，映射不到 StageName）。
        captureRuntimeStageTraces(ctx, runtimeCtx.getStageTraces());

        // ⑦ 子图失败照旧抛（与 runtime 路径一致语义）。
        StageTrace failed = firstFailedTrace(runtimeCtx.getStageTraces());
        if (failed != null) {
            throw new IllegalStateException("multi-agent node failed: " + failed.stage()
                    + (failed.message() != null && !failed.message().isBlank() ? " - " + failed.message() : ""));
        }
    }

    /**
     * Knowledge Agent 的 I/O 边界。retrieve 复用 {@link #retrieveService} + {@link EvidencePackage#from}，
     * 阈值按子图给的 attempt 决定（0=正常阈值，&gt;0=降级阈值），改写提示按 relevanceReplan 决定；
     * 产物写进 ctx（供合成 stageEvents 与后续 Analyst prompt 用）并塞进 holder。judge 复用相关性裁判，
     * 任何内部失败回退 passThrough。
     */
    private KnowledgeAgentDelegate buildKnowledgeDelegate(WorkflowTurnState ctx, AtomicReference<EvidencePackage> holder) {
        return new KnowledgeAgentDelegate() {
            @Override
            public EvidencePackage retrieve(int attempt, boolean relevanceReplan) {
                long t0 = System.nanoTime();
                log.info("[stage] RETRIEVE start attempt={} relevanceReplan={} requestId={}",
                        attempt, relevanceReplan, ctx.requestId);
                ctx.stageEvents.add(StageEvent.start(StageName.RETRIEVE, ctx.requestId));

                ctx.currentUser = SecurityContextHolder.getContext().getAuthentication() != null
                        ? SecurityContextHolder.getContext().getAuthentication().getName()
                        : "anonymous";
                double effectiveThreshold = attempt > 0
                        ? appAgentProperties.getRag().getReplanSimilarityThreshold()
                        : similarityThreshold;
                if (attempt > 0) {
                    log.info("[stage] RETRIEVE replan threshold {}->{} attempt={} requestId={}",
                            similarityThreshold, effectiveThreshold, attempt, ctx.requestId);
                }
                String retrieveMessage = ctx.userMessage;
                if (relevanceReplan) {
                    retrieveMessage = REWRITE_HINT_PREFIX + (ctx.userMessage == null ? "" : ctx.userMessage);
                    log.info("[stage] RETRIEVE relevance-replan rewrite-hint applied requestId={}", ctx.requestId);
                }
                RetrieveResult result = retrieveService.retrieve(new RetrieveRequest(
                        retrieveMessage,
                        ctx.currentUser,
                        ctx.requestId,
                        maxContextDocs,
                        topKPerQuery,
                        effectiveThreshold
                ));
                EvidencePackage evidence = EvidencePackage.from(result);
                // 写回 ctx：用于合成 stageEvents（doc 数/elapsed）与 Analyst prompt 拼装的入参一致性。
                ctx.currentUser = evidence.currentUser();
                ctx.userFilter = new Filter.Expression(
                        Filter.ExpressionType.EQ,
                        new Filter.Key("user_id"),
                        new Filter.Value(ctx.currentUser)
                );
                ctx.queries = evidence.queries();
                ctx.docs = evidence.docs();
                ctx.promptBase = evidence.promptBase();
                ctx.citationBlock = evidence.citationBlock();
                ctx.rewriteMs = result.rewriteMs();
                ctx.retrieveMs = evidence.retrieveMs();
                holder.set(evidence);

                ctx.stageEvents.add(StageEvent.end(StageName.RETRIEVE, ctx.requestId,
                        ctx.stageElapsedMs.getOrDefault(StageName.RETRIEVE, 0L)));
                logPreWriteStageBoundary(StageName.RETRIEVE, t0, ctx);
                return evidence;
            }

            @Override
            public RetrievalRelevanceJudge.Verdict judge(List<Document> docs) {
                return relevanceJudge.judge(ctx.userMessage, docs);
            }
        };
    }

    /**
     * Analyst Agent 的 I/O 边界。invokeTool 复用 {@link #toolInvocationService}，工具 trace/policyEvents
     * 收集到外层 ctx（不进 Agent 产物契约）。guard 是<strong>纯澄清</strong>——只把 {@link GuardDecisionResult}
     * 的 skipLlm/clarifyBody 回传，<strong>不做任何 replan</strong>（replan 整个归 Knowledge）。assemblePrompt
     * 复用 {@link #promptAssemblyService}。skipTool 时套用 {@link #applyToolSkippedState} 语义（空前言）。
     */
    private AnalystAgentDelegate buildAnalystDelegate(WorkflowTurnState ctx, PlanStageNode.PhysicalStageFlags flags) {
        return new AnalystAgentDelegate() {
            @Override
            public void invokeTool() {
                if (!flags.runTool()) {
                    long t0 = System.nanoTime();
                    log.info("[stage] TOOL skipped_by_plan requestId={}", ctx.requestId);
                    ctx.toolPreface = "";
                    ctx.stageEvents.add(StageEvent.skip(StageName.TOOL, ctx.requestId, "skipped_by_plan"));
                    logPreWriteStageBoundary(StageName.TOOL, t0, ctx);
                    return;
                }
                long t0 = System.nanoTime();
                log.info("[stage] TOOL start requestId={}", ctx.requestId);
                ctx.stageEvents.add(StageEvent.start(StageName.TOOL, ctx.requestId));
                ToolInvocationResult invocation = toolInvocationService.invoke(new ToolInvocationRequest(
                        ctx.userMessage,
                        ctx.requestId,
                        marketDataToolEnabled.getAsBoolean(),
                        marketDataSummaryMaxChars.getAsInt()
                ));
                ctx.toolPreface = invocation.toolPreface();
                if (invocation.toolResult() != null) {
                    log(log, invocation.toolResult(), ctx.requestId);
                    captureRuntimeToolTrace(ctx, invocation.toolResult());
                }
                ctx.policyEvents.addAll(invocation.policyEvents());
                ctx.stageEvents.add(StageEvent.end(StageName.TOOL, ctx.requestId,
                        ctx.stageElapsedMs.getOrDefault(StageName.TOOL, 0L)));
                logPreWriteStageBoundary(StageName.TOOL, t0, ctx);
            }

            @Override
            public GuardOutcome guard(EvidencePackage evidence) {
                if (!flags.runGuard()) {
                    log.info("[stage] GUARD skipped_by_plan requestId={}", ctx.requestId);
                    ctx.stageEvents.add(StageEvent.skip(StageName.GUARD, ctx.requestId, "skipped_by_plan"));
                    return new GuardOutcome(false, "");
                }
                long t0 = System.nanoTime();
                log.info("[stage] GUARD start requestId={}", ctx.requestId);
                ctx.stageEvents.add(StageEvent.start(StageName.GUARD, ctx.requestId));
                GuardDecisionResult decision = guardDecisionService.decide(new GuardDecisionRequest(
                        evidence.docs(),
                        ctx.toolPreface,
                        emptyHitsBehavior.get(),
                        ctx.requestId
                ));
                // 纯澄清：replan 不在这里（归 Knowledge）。只落 skipLlm/clarifyBody 与可观测。
                ctx.emptyHitsGateLogCode = decision.emptyHitsGateLogCode();
                ctx.policyEvents.addAll(decision.policyEvents());
                ctx.stageEvents.add(StageEvent.end(StageName.GUARD, ctx.requestId,
                        ctx.stageElapsedMs.getOrDefault(StageName.GUARD, 0L)));
                logPreWriteStageBoundary(StageName.GUARD, t0, ctx);
                return new GuardOutcome(decision.skipLlm(),
                        decision.clarifyBody() != null ? decision.clarifyBody() : "");
            }

            @Override
            public String assemblePrompt(EvidencePackage evidence) {
                PromptAssemblyResult result = promptAssemblyService.assemble(new PromptAssemblyRequest(
                        ctx.currentUser,
                        ctx.toolPreface,
                        ctx.planJson,
                        evidence.promptBase()
                ));
                return result.finalPromptForLlm();
            }
        };
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
        // Replan 重查：本轮 GUARD 已判定假零命中并跳回时，用降级阈值放宽召回，判别真缺失/假零命中。
        boolean replanned = ctx.retrievalReplanCount > 0;
        double effectiveThreshold = replanned
                ? appAgentProperties.getRag().getReplanSimilarityThreshold()
                : similarityThreshold;
        if (replanned) {
            log.info("[stage] RETRIEVE replan threshold {}->{} attempt={} requestId={}",
                    similarityThreshold, effectiveThreshold, ctx.retrievalReplanCount, ctx.requestId);
        }
        // 第二刀「换个问法」：相关性 replan 时给 query 改写加提示前缀，让 rewriter 产出不同角度的检索串
        // （此处病根是「问法没问对」而非阈值太严，故核心是换 query，降阈值只是顺带放宽）。
        String retrieveMessage = ctx.userMessage;
        if (ctx.relevanceReplanRequested) {
            retrieveMessage = REWRITE_HINT_PREFIX + (ctx.userMessage == null ? "" : ctx.userMessage);
            log.info("[stage] RETRIEVE relevance-replan rewrite-hint applied requestId={}", ctx.requestId);
            ctx.relevanceReplanRequested = false; // 一次性消费，避免污染后续判断
        }
        RetrieveResult result = retrieveService.retrieve(new RetrieveRequest(
                retrieveMessage,
                ctx.currentUser,
                ctx.requestId,
                maxContextDocs,
                topKPerQuery,
                effectiveThreshold
        ));
        // 阶段 1 等价重构：先把 Knowledge 产出装进「文件袋」EvidencePackage，再立刻解包回 ctx。
        // 这一步刻意不改行为，只为先证明「打包→拆包」整条链路通且不丢字段；阶段 2 才把打包动作
        // 搬进 KnowledgeAgentNode 内部，让 Analyst 只能读这个袋子（真隔离）。
        // rewriteMs 是 Knowledge 内部细节、不进袋，故仍从 result 直接取。
        EvidencePackage evidence = EvidencePackage.from(result);
        ctx.currentUser = evidence.currentUser();
        ctx.userFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("user_id"),
                new Filter.Value(ctx.currentUser)
        );
        ctx.queries = evidence.queries();
        ctx.docs = evidence.docs();
        ctx.promptBase = evidence.promptBase();
        ctx.citationBlock = evidence.citationBlock();
        ctx.rewriteMs = result.rewriteMs();
        ctx.retrieveMs = evidence.retrieveMs();

        logPreWriteStageBoundary(StageName.RETRIEVE, t0, ctx);
    }

    private void stageTool(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] TOOL start requestId={}", ctx.requestId);

        ToolInvocationResult invocation = toolInvocationService.invoke(new ToolInvocationRequest(
                ctx.userMessage,
                ctx.requestId,
                marketDataToolEnabled.getAsBoolean(),
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

    private boolean stageGuard(WorkflowTurnState ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] GUARD start requestId={}", ctx.requestId);

        GuardDecisionResult decision = guardDecisionService.decide(new GuardDecisionRequest(
                ctx.docs,
                ctx.toolPreface,
                emptyHitsBehavior.get(),
                ctx.requestId
        ));

        // Replan：纯零命中(无工具数据) + 本轮尚未 replan 过 → 建议跳回 RETRIEVE 用降级阈值再探一次。
        // 仅 runtime 图编排路径有回边能承接 redirect；inline 直线基线不具备 replan 能力(能力开关语义)，
        // 故 gate 在 workflowRuntimePath 上，inline 永远 false、照旧落澄清，行为与改动前完全一致。
        // 命中 replan 时刻意不落地澄清状态，把判别机会让给重查；重查后仍空才由下一轮 GUARD 真正落澄清。
        boolean replanRequested = ctx.workflowRuntimePath
                && decision.reason() == RetrieveEmptyHitGate.Reason.APPLIED_CLARIFY_RAG_EMPTY
                && ctx.retrievalReplanCount < MAX_RETRIEVAL_REPLANS;
        if (replanRequested) {
            ctx.retrievalReplanCount++;
            log.info("[guard] replan requested empty_hits gate=replan reason=RAG_EMPTY attempt={} requestId={}",
                    ctx.retrievalReplanCount, ctx.requestId);
            logPreWriteStageBoundary(StageName.GUARD, t0, ctx);
            return true;
        }

        // 第二刀：检索非空但可能「捞错了」——多问一步 LLM 裁判这几条能否支撑回答；
        // 判不相关 → 复用同一条回边「换个问法重检」，与零命中 replan 共用计数器与上界。
        // 仅 runtime 路径有回边；inline 不调裁判（无回边可承接，且省一次 LLM 调用）。
        // fail-safe：裁判不可用(available=false)时绝不触发 replan，避免外部抖动变功能 bug。
        if (ctx.workflowRuntimePath
                && decision.reason() == RetrieveEmptyHitGate.Reason.SKIPPED_HAS_RETRIEVAL_HITS
                && ctx.retrievalReplanCount < MAX_RETRIEVAL_REPLANS) {
            RetrievalRelevanceJudge.Verdict verdict = relevanceJudge.judge(ctx.userMessage, ctx.docs);
            if (verdict.available() && !verdict.relevant()) {
                ctx.retrievalReplanCount++;
                ctx.relevanceReplanRequested = true;
                log.info("[guard] replan requested relevance gate=replan reason=NOT_RELEVANT attempt={} judge_reason='{}' requestId={}",
                        ctx.retrievalReplanCount, verdict.reason(), ctx.requestId);
                logPreWriteStageBoundary(StageName.GUARD, t0, ctx);
                return true;
            }
            if (!verdict.available()) {
                log.info("[guard] relevance judge unavailable, pass-through (no replan) requestId={}", ctx.requestId);
            }
        }

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
        return false;
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
