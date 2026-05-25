package com.travel.ai.agent;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.travel.ai.agent.guard.GuardDecisionRequest;
import com.travel.ai.agent.guard.GuardDecisionResult;
import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.agent.guard.RetrieveEmptyHitGate;
import com.travel.ai.agent.plan.MainLinePlanProposer;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.agent.plan.PlanServiceRequest;
import com.travel.ai.agent.plan.PlanServiceResult;
import com.travel.ai.agent.prompt.PromptAssemblyRequest;
import com.travel.ai.agent.prompt.PromptAssemblyResult;
import com.travel.ai.agent.prompt.PromptAssemblyService;
import com.travel.ai.agent.retrieve.RetrieveRequest;
import com.travel.ai.agent.retrieve.RetrieveResult;
import com.travel.ai.agent.retrieve.RetrieveService;
import com.travel.ai.agent.tool.ToolInvocationRequest;
import com.travel.ai.agent.tool.ToolInvocationResult;
import com.travel.ai.agent.tool.ToolInvocationService;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.profile.ProfileExtractionCoordinator;
import com.travel.ai.profile.UserProfileService;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParseException;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.plan.PlanPhysicalStagePolicy;
import com.travel.ai.plan.PlanV1;
import com.travel.ai.config.RedisChatMemory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.PlanParseEvent;
import com.travel.ai.runtime.SseControlEvent;
import com.travel.ai.runtime.LinearWorkflowRuntime;
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
import com.travel.ai.web.RequestTraceFilter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static com.travel.ai.tools.ToolObservability.log;

/**
 * 金融分析 Agent 编排：主线采用<strong>固定线性阶段</strong>（P0-1 编排骨架），逻辑顺序为
 * {@code PLAN → RETRIEVE → TOOL → GUARD → WRITE}，由 {@link TravelAgent#runLinearStages(MainAgentTurnContext)}
 * 串行推进；禁止用「阶段名 → 处理器」的 Map 或动态跳转驱动执行（避免退化成 DAG/状态机）。
 * <p>
 * 在附录 E {@code steps[*].stage} 未声明某阶段时，<strong>物理跳过</strong>该阶段（见 {@link PlanPhysicalStagePolicy}；{@code GUARD}
 * 在含 {@code RETRIEVE} 时仍隐式执行以保留零命中门控）。
 * <p>
 * 大白话：用户一问进来，服务端按固定几步处理——先产出结构化计划（可配置调用 LLM）、再查资料、再按需调工具、再过门控（默认「知识库零命中则澄清不调 LLM」）、最后才调大模型流式写回答。SSE 上在引用与正文之前先发 {@code event: plan_parse} 携带解析元数据，便于与评测对账。
 * <p>
 * 检索合并阶段使用 {@link RetrieveService}：按文档 id（无 id 时退化为正文 hash）
 * 显式去重，避免依赖 {@link Document#equals} 实现细节（UPGRADE P2-2）。
 */
// Historical class name retained for compatibility.
// This implementation backs FinancialAnalystAgent and currently serves the finance analyst workflow.
@Component
public class TravelAgent implements FinancialAnalystAgent {

    private static final Logger log = LoggerFactory.getLogger(TravelAgent.class);

    /** 合并后进入 prompt 的文档条数上限 */
    private static final int MAX_CONTEXT_DOCS = 5;

    /** 固定流水线阶段数：PLAN、RETRIEVE、TOOL、GUARD、WRITE（与 app.agent.max-steps 校验一致）。 */
    private static final int FIXED_PIPELINE_STAGE_COUNT = 5;

    /** SSE {@code event:error}：{@code app.agent.max-steps} 小于固定流水线阶段数。 */
    public static final String ERROR_CODE_SSE_AGENT_CONFIG = "AGENT_CONFIG_ERROR";
    /** SSE {@code event:error}：同步编排（{@link #runLinearStages}）抛出的异常。 */
    public static final String ERROR_CODE_SSE_AGENT_PIPELINE = "AGENT_PIPELINE_ERROR";
    /** SSE {@code event:error}：Reactor 流式链路中非整轮超时的异常。 */
    public static final String ERROR_CODE_SSE_AGENT_STREAM = "AGENT_STREAM_ERROR";
    /** SSE {@code event:error}：整轮墙钟超时（与 {@link #chat} 外层 {@code .timeout(total)} 对齐）。 */
    public static final String ERROR_CODE_SSE_AGENT_TOTAL_TIMEOUT = "AGENT_TOTAL_TIMEOUT";
    /** SSE {@code event:error}：LLM 子流 {@code .timeout(llm_stream)} 触发（与整轮 {@link #ERROR_CODE_SSE_AGENT_TOTAL_TIMEOUT} 区分）。 */
    public static final String ERROR_CODE_SSE_AGENT_LLM_STREAM_TIMEOUT = "AGENT_LLM_STREAM_TIMEOUT";
    /** SSE {@code event:error}：LLM 子流非超时异常（降级为占位文本前注入）。 */
    public static final String ERROR_CODE_SSE_AGENT_LLM_STREAM_ERROR = "AGENT_LLM_STREAM_ERROR";

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final WeatherTool weatherTool;
    private final MarketDataTool marketDataTool;
    private final com.travel.ai.tools.ToolCircuitBreaker toolCircuitBreaker;
    private final com.travel.ai.tools.ToolRateLimiter toolRateLimiter;
    private final PlanParser planParser;
    private final AppAgentProperties appAgentProperties;
    private final ProfileExtractionCoordinator profileExtractionCoordinator;
    private final LinearWorkflowRuntime linearWorkflowRuntime = new LinearWorkflowRuntime();
    private final GuardDecisionService guardDecisionService = new GuardDecisionService();
    private final PlanService planService;
    private final RetrieveService retrieveService;
    private final ToolInvocationService toolInvocationService;
    private final PromptAssemblyService promptAssemblyService;

    @Value("${app.tools.weather.enabled:true}")
    private boolean weatherToolEnabled;

    @Value("${app.tools.weather.summary-max-chars:400}")
    private int weatherSummaryMaxChars;

    @Value("${app.tools.market.enabled:true}")
    private boolean marketDataToolEnabled;

    @Value("${app.tools.market.summary-max-chars:600}")
    private int marketDataSummaryMaxChars;

    /** 长连接空闲时定期发送 SSE 注释行（comment），避免网关/代理因无数据而断开 */
    @Value("${app.sse.heartbeat-seconds:15}")
    private int sseHeartbeatSeconds;

    /**
     * 检索零命中时策略：{@code clarify}（默认）= 不调 LLM 做开放式行程生成，仅返回澄清文案；
     * {@code allow_answer} = 仍走 LLM（仅用于对照/调试，易与「无引用强答」冲突）。
     */
    @Value("${app.rag.empty-hits-behavior:clarify}")
    private String emptyHitsBehavior;

    private static final String SYSTEM_PROMPT = """
            你是 Financial AI Analyst / Research Agent，面向金融知识学习、新闻分析、财报/研报问答、市场数据解读和风险提示。
            回答必须遵守：
            1. 优先基于检索到的资料、工具观察和用户提供的信息作答；证据不足时先澄清，不要编造。
            2. 明确引用来源或引用片段，区分事实、推断与不确定性。
            3. 涉及市场、公司、财务、宏观或新闻分析时，说明数据时点、关键假设和主要风险。
            4. 不提供个性化投资建议，不承诺收益，不给出自动交易或下单指令。
            5. 默认附上简短风险提示：内容仅供研究和教育参考，不构成投资建议。
            请用清晰的结构化格式回答，方便用户复核证据。
            """;

    public TravelAgent(ChatClient.Builder builder,
                       RedisChatMemory chatMemory,
                       VectorStore vectorStore,
                       QueryRewriter queryRewriter,
                       WeatherTool weatherTool,
                       MarketDataTool marketDataTool,
                       com.travel.ai.tools.ToolCircuitBreaker toolCircuitBreaker,
                       com.travel.ai.tools.ToolRateLimiter toolRateLimiter,
                       MainLinePlanProposer mainLinePlanProposer,
                       PlanParseCoordinator planParseCoordinator,
                       PlanParser planParser,
                       AppAgentProperties appAgentProperties,
                       UserProfileService userProfileService,
                       ProfileExtractionCoordinator profileExtractionCoordinator) {
        this.chatMemory = chatMemory;
        this.weatherTool = weatherTool;
        this.marketDataTool = marketDataTool;
        this.toolCircuitBreaker = toolCircuitBreaker;
        this.toolRateLimiter = toolRateLimiter;
        this.planParser = planParser;
        this.appAgentProperties = appAgentProperties;
        this.profileExtractionCoordinator = profileExtractionCoordinator;
        this.planService = new PlanService(mainLinePlanProposer, planParseCoordinator);
        this.retrieveService = new RetrieveService(queryRewriter, vectorStore);
        this.toolInvocationService = new ToolInvocationService(
                weatherTool,
                marketDataTool,
                toolCircuitBreaker,
                toolRateLimiter
        );
        this.promptAssemblyService = new PromptAssemblyService(userProfileService);
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultTools(weatherTool)
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .topP(0.7)
                                .build()
                )
                .build();
    }

    /**
     * 单轮 SSE 对话入口：只做 MDC、构造 {@link MainAgentTurnContext}，再按固定顺序跑阶段，最后组装 SSE。
     */
    @Override
    public Flux<ServerSentEvent<String>> chat(String conversationId, String userMessage) {
        String requestId = RequestTraceFilter.currentRequestIdOrNew();

        int agentMaxSteps = appAgentProperties.getMaxSteps();
        Duration agentTotalTimeout = appAgentProperties.getTotalTimeout();
        Duration llmStreamTimeout = appAgentProperties.getLlmStreamTimeout();

        if (agentMaxSteps < FIXED_PIPELINE_STAGE_COUNT) {
            log.error("[agent] app.agent.max-steps={} < fixed_pipeline_steps={} requestId={}",
                    agentMaxSteps, FIXED_PIPELINE_STAGE_COUNT, requestId);
            Flux<ServerSentEvent<String>> err = Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(SseControlEvent.error(
                            requestId,
                            ERROR_CODE_SSE_AGENT_CONFIG,
                            "服务端配置 app.agent.max-steps 过小，无法完成本轮编排，请联系管理员。"
                    ).toSseJson())
                    .build());
            Flux<ServerSentEvent<String>> fallback = Flux.just(ServerSentEvent.<String>builder()
                    .data("【系统提示】服务端配置 app.agent.max-steps 过小，无法完成本轮编排，请联系管理员。")
                    .build());
            Flux<ServerSentEvent<String>> done = Flux.just(ServerSentEvent.<String>builder()
                    .event("done")
                    .data(SseControlEvent.done(requestId).toSseJson())
                    .build());
            return Flux.concat(err, fallback, done)
                    .doFinally(signalType -> MDC.remove("requestId"));
        }

        log.info("调用AI，conversationId={}, requestId={}, message={}", conversationId, requestId, userMessage);
        log.info("[agent] timeout_config total={} llm_stream={} max_steps={} (pipeline_steps={}) requestId={}",
                agentTotalTimeout, llmStreamTimeout, agentMaxSteps, FIXED_PIPELINE_STAGE_COUNT, requestId);

        final String profileExtractionUsername = currentUsernameForProfileHook();

        MainAgentTurnContext ctx = new MainAgentTurnContext(conversationId, userMessage, requestId);
        try {
            if (shouldUseWorkflowRuntime(appAgentProperties)) {
                runLinearStagesWithRuntime(ctx);
            } else {
                runLinearStages(ctx);
            }
        } catch (Exception e) {
            log.error("[agent] pipeline_failed requestId={} err={}", requestId, e.toString());
            Flux<ServerSentEvent<String>> err = Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(SseControlEvent.error(
                            requestId,
                            ERROR_CODE_SSE_AGENT_PIPELINE,
                            "服务端处理异常，请稍后重试。"
                    ).toSseJson())
                    .build());
            Flux<ServerSentEvent<String>> fallback = Flux.just(ServerSentEvent.<String>builder()
                    .data("【系统提示】服务端处理异常，请稍后重试。")
                    .build());
            Flux<ServerSentEvent<String>> done = Flux.just(ServerSentEvent.<String>builder()
                    .event("done")
                    .data(SseControlEvent.done(requestId).toSseJson())
                    .build());
            return Flux.concat(err, fallback, done)
                    .doFinally(signalType -> MDC.remove("requestId"));
        }

        if (ctx.skipLlmForEmptyHits) {
            String gateCode = ctx.emptyHitsGateLogCode != null
                    ? ctx.emptyHitsGateLogCode
                    : RetrieveEmptyHitGate.ERROR_CODE_RETRIEVE_EMPTY;
            log.info("SKIP_LLM empty_hits_gate error_code={} would_prompt_chars={} requestId={}",
                    gateCode, ctx.finalPromptForLlm.length(), requestId);
            // 必须同步写入：挂在 Flux doOnComplete 上会与 merge/then 多订阅叠加，出现重复 add（Redis 多条相同轮次）。
            appendTurnToMemory(ctx);
        } else {
            log.info("最终 prompt 字符数={}", ctx.finalPromptForLlm.length());
        }

        AtomicLong llmStartNs = new AtomicLong();
        AtomicBoolean firstLlmToken = new AtomicBoolean(true);

        ctx.stageEvents.add(StageEvent.start(StageName.WRITE, ctx.requestId));
        Flux<String> contentFlux = stageWrite(ctx, llmStreamTimeout, llmStartNs, firstLlmToken);

        AtomicBoolean llmStreamErrorSseEmitted = new AtomicBoolean(false);
        Flux<ServerSentEvent<String>> tokenEvents = contentFlux.flatMap(chunk -> {
            String code = ctx.llmStreamErrorCode.get();
            if (code != null && llmStreamErrorSseEmitted.compareAndSet(false, true)) {
                String msg = "当前 AI 响应较慢或出现异常，请稍后重试。";
                return Flux.concat(
                        Flux.just(ServerSentEvent.<String>builder()
                                .event("error")
                                .data(SseControlEvent.error(requestId, code, msg).toSseJson())
                                .build()),
                        Flux.just(ServerSentEvent.<String>builder().data(chunk).build())
                );
            }
            return Flux.just(ServerSentEvent.<String>builder().data(chunk).build());
        });

        Flux<ServerSentEvent<String>> planParseMetaFlux = Flux.just(buildPlanParseMetaEvent(ctx));

        Flux<ServerSentEvent<String>> stageEventsFlux = Flux.fromIterable(ctx.stageEvents)
                .map(e -> ServerSentEvent.<String>builder()
                        .event("stage")
                        .data(e.toSseJson())
                        .build());

        Flux<ServerSentEvent<String>> policyEventsFlux = Flux.fromIterable(ctx.policyEvents)
                .map(e -> ServerSentEvent.<String>builder()
                        .event("policy")
                        .data(e.toSseJson())
                        .build());

        Flux<ServerSentEvent<String>> citationFlux = Flux.just(
                ServerSentEvent.<String>builder().data(ctx.citationBlock).build()
        );

        Flux<ServerSentEvent<String>> keepAlive = Flux.interval(Duration.ofSeconds(Math.max(1, sseHeartbeatSeconds)))
                .takeUntilOther(contentFlux.then())
                .map(tick -> ServerSentEvent.<String>builder().comment("keepalive").build());

        Flux<ServerSentEvent<String>> merged = Flux.concat(planParseMetaFlux, stageEventsFlux, policyEventsFlux, citationFlux, Flux.merge(tokenEvents, keepAlive));
        Flux<ServerSentEvent<String>> done = Flux.just(ServerSentEvent.<String>builder()
                .event("done")
                .data(SseControlEvent.done(requestId).toSseJson())
                .build());
        return merged
                .timeout(agentTotalTimeout)
                .onErrorResume(t -> isTotalTimeout(t),
                        t -> {
                            log.warn("[agent] total_timeout elapsed limit={} requestId={} error={}",
                                    agentTotalTimeout, requestId, t.toString());
                            Flux<ServerSentEvent<String>> err = Flux.just(ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data(SseControlEvent.error(
                                            requestId,
                                            ERROR_CODE_SSE_AGENT_TOTAL_TIMEOUT,
                                            "本轮对话处理超时，请简化问题后重试。"
                                    ).toSseJson())
                                    .build());
                            Flux<ServerSentEvent<String>> fallback = Flux.just(ServerSentEvent.<String>builder()
                                    .data("【系统提示】本轮对话处理超时，请简化问题后重试。")
                                    .build());
                            return Flux.concat(err, fallback);
                        })
                .onErrorResume(t -> {
                            log.warn("[agent] non_timeout_error requestId={} err={}", requestId, t.toString());
                            Flux<ServerSentEvent<String>> err = Flux.just(ServerSentEvent.<String>builder()
                                    .event("error")
                                    .data(SseControlEvent.error(
                                            requestId,
                                            ERROR_CODE_SSE_AGENT_STREAM,
                                            "服务端处理异常，请稍后重试。"
                                    ).toSseJson())
                                    .build());
                            Flux<ServerSentEvent<String>> fallback = Flux.just(ServerSentEvent.<String>builder()
                                    .data("【系统提示】服务端处理异常，请稍后重试。")
                                    .build());
                            return Flux.concat(err, fallback);
                        })
                .concatWith(done)
                .doOnCancel(() -> log.info("SSE 订阅已取消（多为客户端断开），conversationId={}, requestId={}", conversationId, requestId))
                .doOnComplete(() -> profileExtractionCoordinator.onChatCompleted(profileExtractionUsername, conversationId, requestId))
                .doFinally(signalType -> MDC.remove("requestId"));
    }

    private static String currentUsernameForProfileHook() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getName();
    }

    private static boolean isTotalTimeout(Throwable t) {
        if (t instanceof TimeoutException) {
            return true;
        }
        Throwable c = t.getCause();
        return c instanceof TimeoutException;
    }

    private static boolean hasTimeoutCause(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            if (x instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * P0-1：在固定顺序下串行推进各阶段；是否<strong>真正执行</strong> RETRIEVE/TOOL/GUARD 由解析后的 plan {@code steps} 决定
     * （{@link PlanPhysicalStagePolicy}）。
     */
    private void runLinearStages(MainAgentTurnContext ctx) {
        ctx.stageEvents.add(StageEvent.start(StageName.PLAN, ctx.requestId));
        stagePlan(ctx);
        // stagePlan 内部会记录 plan_parse_* 元数据；阶段结束事件在此统一写入
        ctx.stageEvents.add(StageEvent.end(StageName.PLAN, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.PLAN, 0L)));
        PlanV1 planV1;
        try {
            planV1 = planParser.parse(ctx.planJson);
        } catch (PlanParseException e) {
            throw new IllegalStateException("plan must parse after PlanParseCoordinator", e);
        }
        PlanPhysicalStagePolicy.PhysicalStageFlags f = PlanPhysicalStagePolicy.resolve(planV1);
        log.info("[agent] physical_stages retrieve={} tool={} guard={} requestId={}",
                f.runRetrieve(), f.runTool(), f.runGuard(), ctx.requestId);

        if (f.runRetrieve()) {
            ctx.stageEvents.add(StageEvent.start(StageName.RETRIEVE, ctx.requestId));
            stageRetrieve(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.RETRIEVE, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.RETRIEVE, 0L)));
        } else {
            applyRetrieveSkipped(ctx);
        }
        if (f.runTool()) {
            ctx.stageEvents.add(StageEvent.start(StageName.TOOL, ctx.requestId));
            stageTool(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.TOOL, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.TOOL, 0L)));
        } else {
            applyToolSkipped(ctx);
        }
        if (f.runGuard()) {
            ctx.stageEvents.add(StageEvent.start(StageName.GUARD, ctx.requestId));
            stageGuard(ctx);
            ctx.stageEvents.add(StageEvent.end(StageName.GUARD, ctx.requestId, ctx.stageElapsedMs.getOrDefault(StageName.GUARD, 0L)));
        } else {
            log.info("[stage] GUARD skipped_by_plan requestId={}", ctx.requestId);
            ctx.stageEvents.add(StageEvent.skip(StageName.GUARD, ctx.requestId, "skipped_by_plan"));
        }
    }

    private void runLinearStagesWithRuntime(MainAgentTurnContext ctx) {
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
                        TravelAgent.this.stagePlan(ctx);
                        PlanPhysicalStagePolicy.PhysicalStageFlags flags = TravelAgent.this.physicalStageFlags(ctx);
                        return new PlanStageNode.PhysicalStageFlags(
                                flags.runRetrieve(),
                                flags.runTool(),
                                flags.runGuard()
                        );
                    }

                    @Override
                    public void onPhysicalStageFlagsResolved(PlanStageNode.PhysicalStageFlags flags) {
                        log.info("[agent] physical_stages retrieve={} tool={} guard={} requestId={}",
                                flags.runRetrieve(), flags.runTool(), flags.runGuard(), ctx.requestId);
                    }
                }),
                new RetrieveStageNode(new RetrieveStageNode.RetrieveStageDelegate() {
                    @Override
                    public void stageRetrieve() {
                        TravelAgent.this.stageRetrieve(ctx);
                    }

                    @Override
                    public void onRetrieveSkippedByPlan() {
                        TravelAgent.this.applyRetrieveSkippedState(ctx);
                    }
                }),
                new ToolStageNode(new ToolStageNode.ToolStageDelegate() {
                    @Override
                    public void stageTool() {
                        TravelAgent.this.stageTool(ctx);
                    }

                    @Override
                    public void onToolSkippedByPlan() {
                        TravelAgent.this.applyToolSkippedState(ctx);
                    }
                }),
                new GuardStageNode(new GuardStageNode.GuardStageDelegate() {
                    @Override
                    public void stageGuard() {
                        TravelAgent.this.stageGuard(ctx);
                    }

                    @Override
                    public void onGuardSkippedByPlan() {
                        log.info("[stage] GUARD skipped_by_plan requestId={}", ctx.requestId);
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

    static boolean shouldUseWorkflowRuntime(AppAgentProperties properties) {
        return properties != null && properties.getWorkflowRuntime().isEnabled();
    }

    static void captureRuntimeStageTraces(MainAgentTurnContext ctx, List<StageTrace> traces) {
        if (ctx == null || traces == null || traces.isEmpty()) {
            return;
        }
        ctx.runtimeStageTraces.clear();
        ctx.runtimeStageTraces.addAll(traces);
    }

    static void captureRuntimeToolTrace(MainAgentTurnContext ctx, ToolResult result) {
        if (ctx == null || !ctx.workflowRuntimePath || result == null) {
            return;
        }
        ToolTrace trace = RuntimeTraceMapper.toToolTrace(result);
        if (trace != null) {
            ctx.runtimeToolTraces.add(trace);
        }
    }

    static List<StageEvent> toStageEventsForRuntime(List<StageTrace> traces, String requestId) {
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
                        com.travel.ai.runtime.StageEventKind.ERROR,
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

    private void applyRetrieveSkipped(MainAgentTurnContext ctx) {
        applyRetrieveSkippedState(ctx);
        ctx.stageEvents.add(StageEvent.skip(StageName.RETRIEVE, ctx.requestId, "skipped_by_plan"));
    }

    private void applyRetrieveSkippedState(MainAgentTurnContext ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] RETRIEVE skipped_by_plan requestId={}", ctx.requestId);
        ctx.queries = List.of();
        ctx.rewriteMs = 0;
        ctx.currentUser = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName()
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
        logStageBoundary(StageName.RETRIEVE, t0, ctx);
    }

    private void applyToolSkipped(MainAgentTurnContext ctx) {
        applyToolSkippedState(ctx);
        ctx.stageEvents.add(StageEvent.skip(StageName.TOOL, ctx.requestId, "skipped_by_plan"));
    }

    private void applyToolSkippedState(MainAgentTurnContext ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] TOOL skipped_by_plan requestId={}", ctx.requestId);
        ctx.toolPreface = "";
        mergeFinalPromptFromCtx(ctx);
        logStageBoundary(StageName.TOOL, t0, ctx);
    }

    private void mergeFinalPromptFromCtx(MainAgentTurnContext ctx) {
        PromptAssemblyResult result = promptAssemblyService.assemble(new PromptAssemblyRequest(
                ctx.currentUser,
                ctx.toolPreface,
                ctx.planJson,
                ctx.promptBase
        ));
        ctx.finalPromptForLlm = result.finalPromptForLlm();
    }

    private void logStageBoundary(StageName stage, long startNs, MainAgentTurnContext ctx) {
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        String requestId = ctx != null ? ctx.requestId : "";
        log.info("[stage] {} done elapsed_ms={} requestId={}", stage, ms, requestId);
        if (ctx != null && stage != null) {
            ctx.stageElapsedMs.put(stage, ms);
        }
    }

    /**
     * PLAN：调用 {@link MainLinePlanProposer} 产出结构化 Plan JSON（与后续 RETRIEVE/TOOL 并行写入 prompt）；
     * {@code app.agent.plan-stage.enabled=false} 或模型失败时使用本地降级 JSON；最后经 {@link PlanParseCoordinator}
     * 做附录 E 校验与至多一次 repair（与评测路径一致），仍失败则再降级为内置合法 JSON。
     */
    private void stagePlan(MainAgentTurnContext ctx) {
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
        logStageBoundary(StageName.PLAN, t0, ctx);
    }

    /**
     * 与评测 {@code meta.plan_parse_outcome} / {@code meta.plan_parse_attempts} 同名字段，另含 {@code plan_draft_source}、
     * {@code plan_parse_resolved}（与日志 {@code [plan]} {@code resolved=} 对齐），便于 harness 对账。
     */
    private static ServerSentEvent<String> buildPlanParseMetaEvent(MainAgentTurnContext ctx) {
        PlanParseEvent e = PlanParseEvent.of(
                ctx.planParseOutcome,
                ctx.planParseAttempts,
                ctx.planDraftSource,
                ctx.planParseResolved,
                ctx.requestId
        );
        return ServerSentEvent.<String>builder()
                .event("plan_parse")
                .data(e.toSseJson())
                .build();
    }

    /**
     * RETRIEVE：查询改写 + 向量检索 + 去重截断 + 拼出不带工具前缀的 {@code promptBase}，并生成 SSE 用 {@code citationBlock}。
     */
    private void stageRetrieve(MainAgentTurnContext ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] RETRIEVE start requestId={}", ctx.requestId);

        ctx.currentUser = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication() != null
                ? org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getName()
                : "anonymous";
        RetrieveResult result = retrieveService.retrieve(new RetrieveRequest(
                ctx.userMessage,
                ctx.currentUser,
                ctx.requestId,
                MAX_CONTEXT_DOCS,
                2
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

        logStageBoundary(StageName.RETRIEVE, t0, ctx);
    }

    /**
     * TOOL：系统受控工具；产出 {@code toolPreface}，与 PLAN 的 {@code planJson} 及 {@code promptBase} 合并为 {@code finalPromptForLlm}。
     */
    private void stageTool(MainAgentTurnContext ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] TOOL start requestId={}", ctx.requestId);

        ToolInvocationResult invocation = toolInvocationService.invoke(new ToolInvocationRequest(
                ctx.userMessage,
                ctx.requestId,
                weatherToolEnabled,
                marketDataToolEnabled,
                weatherSummaryMaxChars,
                marketDataSummaryMaxChars
        ));
        ctx.toolPreface = invocation.toolPreface();
        if (invocation.toolResult() != null) {
            log(log, invocation.toolResult(), ctx.requestId);
            captureRuntimeToolTrace(ctx, invocation.toolResult());
        }
        ctx.policyEvents.addAll(invocation.policyEvents());

        mergeFinalPromptFromCtx(ctx);

        logStageBoundary(StageName.TOOL, t0, ctx);
    }

    /**
     * GUARD：检索零命中门控（P0 默认 clarify）；仅当 TOOL 的 {@code BEGIN_TOOL_DATA} 与 {@code END_TOOL_DATA} 之间有<strong>非空</strong>正文时放行 LLM，
     * 避免「工具 outcome=ERROR 且 payload 空」仍走大模型编造实时数据。
     */
    private void stageGuard(MainAgentTurnContext ctx) {
        long t0 = System.nanoTime();
        log.info("[stage] GUARD start requestId={}", ctx.requestId);

        GuardDecisionResult decision = guardDecisionService.decide(new GuardDecisionRequest(
                ctx.docs,
                ctx.toolPreface,
                emptyHitsBehavior,
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
                    "[guard] empty_hits_behavior={} no_clarify_gate requestId={}", emptyHitsBehavior, ctx.requestId);
        }

        logStageBoundary(StageName.GUARD, t0, ctx);
    }

    /**
     * WRITE：调用 ChatClient 流式生成（Reactor {@link Flux}）；与心跳共享同一多播上游（LLM 路径 {@code share()}；门控澄清路径 {@code cache()}）。
     * 门控路径的 {@link #appendTurnToMemory} 在 {@link #chat} 中于订阅前同步调用，不在此链路的 {@code doOnComplete} 上挂载。
     */
    private Flux<String> stageWrite(
            MainAgentTurnContext ctx,
            Duration llmTimeout,
            AtomicLong llmStartNs,
            AtomicBoolean firstLlmToken
    ) {
        long t0 = System.nanoTime();

        if (ctx.skipLlmForEmptyHits) {
            log.info("[stage] WRITE start empty_hits_clarify_only requestId={}", ctx.requestId);
            return Flux.just(ctx.emptyHitsClarifyBody)
                    .doOnSubscribe(s -> llmStartNs.set(System.nanoTime()))
                    .doOnNext(chunk -> {
                        if (firstLlmToken.compareAndSet(true, false)) {
                            long ttftMs = (System.nanoTime() - llmStartNs.get()) / 1_000_000L;
                            log.info("[perf] llm_first_token_ms={} requestId={} (clarify_only)", ttftMs, ctx.requestId);
                        }
                    })
                    .doFinally(signal -> {
                        if (llmStartNs.get() != 0L) {
                            long wallMs = (System.nanoTime() - llmStartNs.get()) / 1_000_000L;
                            log.info("[perf] llm_stream_wall_ms={} signal={} requestId={}", wallMs, signal, ctx.requestId);
                        }
                        logStageBoundary(StageName.WRITE, t0, ctx);
                        Long ms = ctx.stageElapsedMs.get(StageName.WRITE);
                        if (ms != null) {
                            ctx.stageEvents.add(StageEvent.end(StageName.WRITE, ctx.requestId, ms));
                        }
                    })
                    // cache：与 merge(..., takeUntilOther(contentFlux.then())) 多路订阅兼容，避免 share refcount 二次订阅导致 doFinally/perf 打两次
                    .cache();
        }

        log.info("[stage] WRITE start requestId={}", ctx.requestId);

        var streamSpec = chatClient.prompt(ctx.finalPromptForLlm)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, ctx.conversationId))
                .stream();

        // Usage logging is post-processing only. Do not subscribe to chatResponse() here, because
        // that can re-enter an incomplete stream advisor chain after the SSE content stream ended.
        // Keep the existing character estimate when provider usage is not already available.
        AtomicLong streamedChars = new AtomicLong(0L);
        long promptChars = ctx.finalPromptForLlm != null ? ctx.finalPromptForLlm.length() : 0L;

        Flux<String> flux = streamSpec
                .content()
                .timeout(llmTimeout)
                .doOnError(t -> {
                    if (hasTimeoutCause(t)) {
                        ctx.llmStreamErrorCode.set(ERROR_CODE_SSE_AGENT_LLM_STREAM_TIMEOUT);
                    } else {
                        ctx.llmStreamErrorCode.set(ERROR_CODE_SSE_AGENT_LLM_STREAM_ERROR);
                    }
                })
                .onErrorResume(throwable -> {
                    log.error("调用 AI 超时或出错，conversationId={}, requestId={}, error={}",
                            ctx.conversationId, ctx.requestId, throwable.toString());
                    return Flux.just("【系统提示】当前 AI 响应较慢或出现异常，请稍后重试。");
                })
                .doOnSubscribe(s -> llmStartNs.set(System.nanoTime()))
                .doOnNext(chunk -> {
                    if (chunk != null) {
                        streamedChars.addAndGet(chunk.length());
                    }
                    if (firstLlmToken.compareAndSet(true, false)) {
                        long ttftMs = (System.nanoTime() - llmStartNs.get()) / 1_000_000L;
                        log.info("[perf] llm_first_token_ms={} requestId={}", ttftMs, ctx.requestId);
                    }
                })
                .doFinally(signal -> {
                    if (llmStartNs.get() != 0L) {
                        long wallMs = (System.nanoTime() - llmStartNs.get()) / 1_000_000L;
                        log.info("[perf] llm_stream_wall_ms={} signal={} requestId={}", wallMs, signal, ctx.requestId);
                    }
                    long totalChars = promptChars + streamedChars.get();
                    long tokenEst = (long) Math.ceil(totalChars / 4.0);
                    log.info("[usage] token_estimate={} prompt_chars={} streamed_chars={} requestId={}",
                            tokenEst, promptChars, streamedChars.get(), ctx.requestId);
                    logStageBoundary(StageName.WRITE, t0, ctx);
                    Long ms = ctx.stageElapsedMs.get(StageName.WRITE);
                    if (ms != null) {
                        ctx.stageEvents.add(StageEvent.end(StageName.WRITE, ctx.requestId, ms));
                    }
                })
                .share();
        return flux;
    }

    /**
     * 零命中门控路径未经过 ChatClient，需自行写入本轮 user/assistant，与 {@link MessageChatMemoryAdvisor} 行为对齐。
     * 由 {@link #chat} 在 {@code skipLlmForEmptyHits} 分支同步调用一次（勿再挂到响应式 {@code doOnComplete}，以免多订阅重复写入 Redis）。
     */
    private void appendTurnToMemory(MainAgentTurnContext ctx) {
        if (!ctx.emptyHitsMemoryWritten.compareAndSet(false, true)) {
            return;
        }
        try {
            chatMemory.add(ctx.conversationId, List.of(
                    new UserMessage(ctx.userMessage),
                    new AssistantMessage(ctx.emptyHitsClarifyBody)
            ));
        } catch (Exception e) {
            log.warn("empty_hits gate: chatMemory.add failed requestId={} error={}", ctx.requestId, e.toString());
        }
    }

    /**
     * 承载单轮对话在各阶段之间传递的状态（mutable context object）。
     * 术语：类似「请求作用域 DTO / turn state」，仅本类各 {@code stage*} 方法写入。
     */
    private PlanPhysicalStagePolicy.PhysicalStageFlags physicalStageFlags(MainAgentTurnContext ctx) {
        PlanV1 planV1;
        try {
            planV1 = planParser.parse(ctx.planJson);
        } catch (PlanParseException e) {
            throw new IllegalStateException("plan must parse after PlanParseCoordinator", e);
        }
        return PlanPhysicalStagePolicy.resolve(planV1);
    }

    static final class MainAgentTurnContext {
        final String conversationId;
        final String userMessage;
        final String requestId;

        /** 主线 SSE 可观测：阶段事件（A 粒度）。在 {@link #runLinearStages} 期间顺序追加，chat() 再一次性拼进 Flux。 */
        final List<StageEvent> stageEvents = new ArrayList<>();
        /** 阶段耗时（毫秒），由 {@link #logStageBoundary} 写入。 */
        final Map<StageName, Long> stageElapsedMs = new LinkedHashMap<>();
        /** 主线 SSE 可观测：策略/决策事件（与 eval meta.policy_events 同语义）。 */
        final List<PolicyEvent> policyEvents = new ArrayList<>();
        /** Runtime R3 internal traces. They are not emitted to SSE or eval in R3B. */
        final List<StageTrace> runtimeStageTraces = new ArrayList<>();
        final List<ToolTrace> runtimeToolTraces = new ArrayList<>();
        boolean workflowRuntimePath;
        /**
         * WRITE 子流：LLM {@code .timeout(llm_stream)} 或其它异常经 onErrorResume 降级为占位文本时，
         * 在 {@link # doOnError} 写入对应 {@code error_code}，供 {@link TravelAgent#chat} 注入 {@code event:error}。
         */
        final AtomicReference<String> llmStreamErrorCode = new AtomicReference<>();

        String currentUser;
        Filter.Expression userFilter;
        List<String> queries;
        long rewriteMs;
        List<Document> docs;
        long retrieveMs;
        /** 不含工具数据块的用户 prompt 片段（检索上下文 + 用户问题）。 */
        String promptBase;
        String toolPreface;
        /** 送入 LLM 的最终 prompt（工具块 + promptBase）。 */
        String finalPromptForLlm;
        /** SSE 首包「引用片段」正文。 */
        String citationBlock;

        /** PLAN 阶段产出的 JSON 文本（含 {@code steps} 数组），并入 WRITE 前最终 prompt。 */
        String planJson;

        /** 与评测 {@code meta} 及 SSE {@code event:plan_parse} 对齐的附录 E 解析结论（由 {@link PlanService} 写回）。 */
        String planDraftSource;
        String planParseOutcome;
        int planParseAttempts;
        String planParseResolved;

        /** 检索零命中且策略为 clarify 时跳过 LLM，仅下发固定澄清。 */
        boolean skipLlmForEmptyHits;
        String emptyHitsClarifyBody;
        /** 与 {@link RetrieveEmptyHitGate.Decision#skipGateErrorCode()} 对齐，仅 skip LLM 时有值。 */
        String emptyHitsGateLogCode;
        /**
         * 门控澄清路径下 {@link #appendTurnToMemory} 挂在 Reactor {@code doOnComplete} 上；merge/多订阅可能触发多次 complete，需保证 Redis 只追加一轮。
         */
        final AtomicBoolean emptyHitsMemoryWritten = new AtomicBoolean(false);

        MainAgentTurnContext(String conversationId, String userMessage, String requestId) {
            this.conversationId = conversationId;
            this.userMessage = userMessage;
            this.requestId = requestId;
            this.toolPreface = "";
            this.skipLlmForEmptyHits = false;
            this.planJson = "";
            this.planDraftSource = "";
            this.planParseOutcome = "";
            this.planParseAttempts = 0;
            this.planParseResolved = "";
        }
    }

    static String guessCityForWeather(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "北京";
        }
        // 仅做最小启发式：若包含“北京/上海/杭州/成都/广州/深圳”，取其一；否则用默认值。
        if (userMessage.contains("北京")) return "北京";
        if (userMessage.contains("上海")) return "上海";
        if (userMessage.contains("杭州")) return "杭州";
        if (userMessage.contains("成都")) return "成都";
        if (userMessage.contains("广州")) return "广州";
        if (userMessage.contains("深圳")) return "深圳";
        return "北京";
    }

}
