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
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.agent.tool.ToolInvocationRequest;
import com.travel.ai.agent.tool.ToolInvocationResult;
import com.travel.ai.agent.tool.ToolInvocationService;
import com.travel.ai.agent.workflow.MainChatWorkflowAdapter;
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
import com.travel.ai.runtime.node.PlanStageNode;
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.runtime.trace.RuntimeTraceMapper;
import com.travel.ai.tools.ToolResult;
import com.travel.ai.web.RequestTraceFilter;

import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static com.travel.ai.tools.ToolObservability.log;

/**
 * 金融分析 Agent 编排：主线采用<strong>固定线性阶段</strong>（P0-1 编排骨架），逻辑顺序为
 * {@code PLAN → RETRIEVE → TOOL → GUARD → WRITE}，由 {@link MainChatWorkflowAdapter}
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

    /** 固定流水线阶段数：PLAN、RETRIEVE、TOOL、GUARD、WRITE（与 app.agent.max-steps 校验一致）。 */
    private static final int FIXED_PIPELINE_STAGE_COUNT = 5;

    /** SSE {@code event:error}：{@code app.agent.max-steps} 小于固定流水线阶段数。 */
    public static final String ERROR_CODE_SSE_AGENT_CONFIG = "AGENT_CONFIG_ERROR";
    /** SSE {@code event:error}：同步编排（pre-WRITE workflow runner）抛出的异常。 */
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
    private final MainChatWorkflowAdapter mainChatWorkflowAdapter;

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
        this.marketDataTool = marketDataTool;
        this.toolCircuitBreaker = toolCircuitBreaker;
        this.toolRateLimiter = toolRateLimiter;
        this.planParser = planParser;
        this.appAgentProperties = appAgentProperties;
        this.profileExtractionCoordinator = profileExtractionCoordinator;
        this.planService = new PlanService(mainLinePlanProposer, planParseCoordinator, planParser);
        this.retrieveService = new RetrieveService(queryRewriter, vectorStore);
        this.toolInvocationService = new ToolInvocationService(
                marketDataTool,
                toolCircuitBreaker,
                toolRateLimiter
        );
        this.promptAssemblyService = new PromptAssemblyService(userProfileService);
        this.mainChatWorkflowAdapter = new MainChatWorkflowAdapter(
                appAgentProperties,
                linearWorkflowRuntime,
                planService,
                retrieveService,
                toolInvocationService,
                guardDecisionService,
                promptAssemblyService,
                () -> marketDataToolEnabled,
                () -> marketDataSummaryMaxChars,
                () -> emptyHitsBehavior,
                appAgentProperties.getRag().getMaxContextDocs(),
                appAgentProperties.getRag().getTopKPerQuery(),
                appAgentProperties.getRag().getSimilarityThreshold()
        );
        this.chatClient = builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build()
                )
                .defaultAdvisors(new SimpleLoggerAdvisor())
                .defaultOptions(
                        DashScopeChatOptions.builder()
                                .topP(0.7)
                                .build()
                )
                .build();
    }

    /**
     * 单轮 SSE 对话入口：只做 MDC、构造 {@link WorkflowTurnState}，再按固定顺序跑阶段，最后组装 SSE。
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

        WorkflowTurnState ctx = new WorkflowTurnState(conversationId, userMessage, requestId);
        try {
            mainChatWorkflowAdapter.runPreWriteWorkflow(ctx);
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

    private void logStageBoundary(StageName stage, long startNs, WorkflowTurnState ctx) {
        long ms = (System.nanoTime() - startNs) / 1_000_000L;
        String requestId = ctx != null ? ctx.requestId : "";
        log.info("[stage] {} done elapsed_ms={} requestId={}", stage, ms, requestId);
        if (ctx != null && stage != null) {
            ctx.stageElapsedMs.put(stage, ms);
        }
    }

    private static ServerSentEvent<String> buildPlanParseMetaEvent(WorkflowTurnState ctx) {
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

    private Flux<String> stageWrite(
            WorkflowTurnState ctx,
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
    private void appendTurnToMemory(WorkflowTurnState ctx) {
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

}
