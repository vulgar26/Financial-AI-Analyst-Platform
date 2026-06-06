package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalCapabilities;
import com.travel.ai.eval.dto.EvalChatMeta;
import com.travel.ai.eval.dto.EvalChatPolicyEvent;
import com.travel.ai.eval.dto.EvalChatRequest;
import com.travel.ai.eval.dto.EvalChatResponse;
import com.travel.ai.eval.dto.EvalChatResultTool;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.PolicyStageAnchor;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import com.travel.ai.eval.dto.EvalChatRetrievalHit;
import com.travel.ai.eval.dto.EvalChatSource;
import com.travel.ai.eval.dto.EvalGuardrailsCapability;
import com.travel.ai.eval.dto.EvalRetrievalCapability;
import com.travel.ai.eval.dto.EvalStreamingCapability;
import com.travel.ai.eval.dto.EvalToolsCapability;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.config.AppEvalProperties;
import com.travel.ai.llm.LlmUsage;
import com.travel.ai.llm.SpringAiUsageExtractor;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParseException;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.plan.PlanPhysicalStagePolicy;
import com.travel.ai.plan.PlanV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.travel.ai.eval.EvalRagGateScenarios.Kind;

/**
 * 评测聊天：Day2 起在 {@link #buildStubResponse(EvalChatRequest)} 内挂载
 * {@link EvalLinearAgentPipeline}，输出 {@code meta.stage_order} / {@code step_count} / {@code replan_count}；
 * plan 解析后按 {@link PlanPhysicalStagePolicy} 与主线一致对未出现在 {@code steps} 中的阶段做<strong>物理跳过</strong>；内置默认 plan 仍含全五段以便既有契约。
 * Reflection / recovery stub：由 {@link EvalReflectionSupport} 写入 {@code meta.recovery_action} 与可选 {@code meta.self_check}（受 {@code app.eval.reflection-meta-enabled} 控制）。
 * Day5 起对非空 query 执行 {@link PlanParseCoordinator}（repair once + {@code plan_parse_attempts/outcome}）；
 * Day6 起在 TOOL 阶段串行执行 {@link EvalToolStageRunner}（超时/失败降级 + {@code tool} / {@code meta.tool_*} / {@code error_code}）。
 * Day7 起支持 {@link EvalRagGateScenarios}：空命中/低置信门控（P0 不启用 score 阈值），{@code meta.low_confidence} + {@code meta.low_confidence_reasons[]}。
 * <p>
 * {@code meta.context_truncation_reasons}：除 snippet / 工具输出外，另含检索候选上限与（非 {@code EVAL} 模式下）改写单行截断归因。
 * Day9 起在 plan 解析成功后执行 {@link EvalQuerySafetyPolicy}：对抗/敏感句式稳定 {@code deny} 或 {@code clarify}，归因见 {@link EvalSafetyErrorCodes}。
 * P1-0 harness：在门控/策略短路点追加 {@code meta.policy_events[]}（{@link EvalChatPolicyEvent}，无敏感原文）。
 * <p>
 * eval-upgrade.md E7：在 {@link EvalMembershipHttpContext} 完整且存在 {@code retrieval_hits} 时写入
 * {@code meta.retrieval_hit_id_hashes} 及配套口径字段。
 */
@Service
public class EvalChatService {

    private static final Logger log = LoggerFactory.getLogger(EvalChatService.class);
    private static final String EVAL_CONTRACT_V1 = "eval_contract_v1";
    private static final String WORKFLOW_MARKET_DATA_EXPLAIN = "market_data_explain";
    private static final String WORKFLOW_MARKET_DATA_VERSION = "v1";
    private static final String WORKFLOW_FAMILY_FINANCE = "finance";
    private static final String FINANCE_GUARD_POLICY_TYPE = "finance_guard";
    private static final String FINANCE_GUARD_RULE_ID = "market_data_mock_disclosure";
    private static final String MARKET_DATA_CONNECTOR = "market_data";
    private static final List<String> EVAL_STAGE_TRACE_ORDER = List.of("PLAN", "RETRIEVE", "TOOL", "GUARD", "WRITE");

    /** 与主线 SSE {@code TravelAgent} 总超时提示对齐的评测归因码。 */
    public static final String ERROR_CODE_AGENT_TOTAL_TIMEOUT = "AGENT_TOTAL_TIMEOUT";

    /** 评测处理过程中线性流水线抛出未预期异常（如检索链路故障），降级为带归因码的 200 而非 500。 */
    public static final String ERROR_CODE_AGENT_INTERNAL_ERROR = "AGENT_INTERNAL_ERROR";

    /** 断点库中 plan 指纹与当前 effective plan 不一致（见 {@link #maybePersistEvalCheckpoint} 写入口径）。 */
    public static final String ERROR_CODE_EVAL_CHECKPOINT_PLAN_MISMATCH = "EVAL_CHECKPOINT_PLAN_MISMATCH";

    /** 断点显示当前 plan 下线性阶段已全部完成，无需续跑。 */
    public static final String ERROR_CODE_EVAL_CHECKPOINT_RESUMED_EXHAUSTED = "EVAL_CHECKPOINT_RESUMED_EXHAUSTED";

    /**
     * 写入 {@link EvalChatMeta#setConfigSnapshotId} 的前缀，与 {@link #stampConfigSnapshotHashOnMeta} 中 SHA-256 小写 hex 拼接。
     */
    static final String CONFIG_SNAPSHOT_ID_PREFIX = "travel-ai:config-snapshot/v1/sha256/";

    private final PlanParseCoordinator planParseCoordinator;
    private final PlanParser planParser;
    private final EvalToolStageRunner evalToolStageRunner;
    private final ObjectProvider<com.travel.ai.agent.QueryRewriter> queryRewriter;
    private final ObjectProvider<VectorStore> vectorStore;
    private final ObjectProvider<ChatClient> evalUsageChatClient;
    private final AppAgentProperties appAgentProperties;
    private final AppEvalProperties appEvalProperties;
    private final ObjectProvider<EvalCheckpointRepository> evalCheckpointRepository;

    public EvalChatService(
            PlanParseCoordinator planParseCoordinator,
            PlanParser planParser,
            EvalToolStageRunner evalToolStageRunner,
            ObjectProvider<com.travel.ai.agent.QueryRewriter> queryRewriter,
            ObjectProvider<VectorStore> vectorStore,
            AppAgentProperties appAgentProperties,
            AppEvalProperties appEvalProperties,
            ObjectProvider<EvalCheckpointRepository> evalCheckpointRepository,
            @org.springframework.beans.factory.annotation.Qualifier("evalUsageChatClient") ObjectProvider<ChatClient> evalUsageChatClient
    ) {
        this.planParseCoordinator = planParseCoordinator;
        this.planParser = planParser;
        this.evalToolStageRunner = evalToolStageRunner;
        this.queryRewriter = queryRewriter;
        this.vectorStore = vectorStore;
        this.appAgentProperties = appAgentProperties;
        this.appEvalProperties = appEvalProperties;
        this.evalCheckpointRepository = evalCheckpointRepository;
        this.evalUsageChatClient = evalUsageChatClient;
    }

    /**
     * 将 {@code latency_ms} 与 {@link EvalChatMeta#getAgentTotalTimeoutMs()} 比较，写入 {@code agent_latency_budget_exceeded}。
     */
    public void applyLatencyBudgetToMeta(EvalChatResponse response, long latencyMs) {
        EvalChatMeta meta = response != null ? response.getMeta() : null;
        if (meta == null) {
            return;
        }
        Long budget = meta.getAgentTotalTimeoutMs();
        if (budget == null || budget <= 0) {
            return;
        }
        boolean exceeded = latencyMs > budget;
        meta.setAgentLatencyBudgetExceeded(exceeded);
        if (exceeded) {
            log.warn("[eval] agent_latency_budget_exceeded latency_ms={} agent_total_timeout_ms={} request_id={}",
                    latencyMs, budget, meta.getRequestId());
        }
    }

    /**
     * 评测整段 {@code app.agent.total-timeout} 触发时返回的轻量响应（不跑检索 / plan / 工具）。
     */
    public EvalChatResponse buildTotalTimeoutStubResponse(EvalChatRequest request, EvalMembershipHttpContext membershipCtx) {
        String mode = request.getMode();
        if (mode == null || mode.isBlank()) {
            mode = "EVAL";
        }
        String requestId = UUID.randomUUID().toString();
        EvalChatMeta meta = new EvalChatMeta(mode, requestId);
        meta.setReplanCount(EvalChatMeta.P0_REPLAN_COUNT);
        stampAgentTimeoutsOnMeta(meta);
        meta.setStageOrder(Collections.emptyList());
        meta.setStepCount(0);
        meta.setAgentLatencyBudgetExceeded(true);

        EvalCapabilities capabilities = new EvalCapabilities(
                new EvalRetrievalCapability(true, false),
                new EvalToolsCapability(true, true),
                new EvalStreamingCapability(false),
                new EvalGuardrailsCapability(false, false, false)
        );
        EvalChatResponse response = new EvalChatResponse();
        response.setCapabilities(capabilities);
        response.setMeta(meta);
        response.setBehavior("clarify");
        response.setErrorCode(ERROR_CODE_AGENT_TOTAL_TIMEOUT);
        response.setAnswer("本轮评测请求处理超时（已达 app.agent.total-timeout），请缩短输入或稍后重试。");
        return complete(response, request, membershipCtx, emptyEvidence());
    }

    /**
     * 流水线抛出未预期异常时的降级响应：返回带 {@link #ERROR_CODE_AGENT_INTERNAL_ERROR} 的 200，
     * 而非让异常冒泡成 500。与 TOOL 超时 / 总超时降级语义一致，避免基础设施抖动（如检索链路故障）
     * 直接打成 500，便于评测侧按 error_code 归因。不回显异常细节，避免泄露内部信息。
     */
    public EvalChatResponse buildInternalErrorStubResponse(EvalChatRequest request, EvalMembershipHttpContext membershipCtx) {
        String mode = request.getMode();
        if (mode == null || mode.isBlank()) {
            mode = "EVAL";
        }
        String requestId = UUID.randomUUID().toString();
        EvalChatMeta meta = new EvalChatMeta(mode, requestId);
        meta.setReplanCount(EvalChatMeta.P0_REPLAN_COUNT);
        stampAgentTimeoutsOnMeta(meta);
        meta.setStageOrder(Collections.emptyList());
        meta.setStepCount(0);

        EvalCapabilities capabilities = new EvalCapabilities(
                new EvalRetrievalCapability(true, false),
                new EvalToolsCapability(true, true),
                new EvalStreamingCapability(false),
                new EvalGuardrailsCapability(false, false, false)
        );
        EvalChatResponse response = new EvalChatResponse();
        response.setCapabilities(capabilities);
        response.setMeta(meta);
        response.setBehavior("clarify");
        response.setErrorCode(ERROR_CODE_AGENT_INTERNAL_ERROR);
        response.setAnswer("本轮评测请求处理过程中发生内部错误，请稍后重试。");
        return complete(response, request, membershipCtx, emptyEvidence());
    }

    private void stampAgentTimeoutsOnMeta(EvalChatMeta meta) {
        meta.setAgentTotalTimeoutMs(appAgentProperties.getTotalTimeout().toMillis());
        meta.setAgentMaxStepsConfigured(appAgentProperties.getMaxSteps());
        meta.setAgentToolTimeoutMs(appAgentProperties.getToolTimeout().toMillis());
        meta.setAgentLlmStreamTimeoutMs(appAgentProperties.getLlmStreamTimeout().toMillis());
        stampConfigSnapshotHashOnMeta(meta);
    }

    /**
     * P1-0 harness：用固定白名单键拼出稳定快照串并取 SHA-256；写入 {@code meta.config_snapshot_hash} 与同源
     * {@code meta.config_snapshot_id}；可选将键值写入 {@code meta.config_snapshot}。\n
     * 密钥类配置永不进入快照；明文对象受 {@code app.eval.config-snapshot-meta-enabled} 控制（默认关）。
     */
    private void stampConfigSnapshotHashOnMeta(EvalChatMeta meta) {
        if (meta == null) {
            return;
        }
        LinkedHashMap<String, String> map = buildConfigSnapshotMap();
        String snapshot = canonicalConfigSnapshotString(map);
        if (snapshot.isBlank()) {
            return;
        }
        String hash = sha256Hex(snapshot);
        if (hash.isBlank()) {
            return;
        }
        meta.setConfigSnapshotHash(hash);
        meta.setConfigSnapshotId(CONFIG_SNAPSHOT_ID_PREFIX + hash);
        meta.setConfigSnapshotHashAlg("SHA-256");
        meta.setConfigSnapshotHashScope("app.agent.* + app.eval.* (safe whitelist)");
        if (appEvalProperties.isConfigSnapshotMetaEnabled()) {
            meta.setConfigSnapshot(Collections.unmodifiableMap(new LinkedHashMap<>(map)));
        }
    }

    /**
     * 与 {@link #canonicalConfigSnapshotString} 键序一致；仅含非密钥白名单项（与 hash 同源）。
     */
    private LinkedHashMap<String, String> buildConfigSnapshotMap() {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        m.put("app.agent.total-timeout-ms", Long.toString(appAgentProperties.getTotalTimeout().toMillis()));
        m.put("app.agent.max-steps", Integer.toString(appAgentProperties.getMaxSteps()));
        m.put("app.agent.tool-timeout-ms", Long.toString(appAgentProperties.getToolTimeout().toMillis()));
        m.put("app.agent.llm-stream-timeout-ms", Long.toString(appAgentProperties.getLlmStreamTimeout().toMillis()));
        m.put("app.eval.tool-timeout-ms", Long.toString(appEvalProperties.getToolTimeoutMs()));
        m.put("app.eval.reflection-meta-enabled", Boolean.toString(appEvalProperties.isReflectionMetaEnabled()));
        m.put("app.eval.stub-work-sleep-ms", Long.toString(appEvalProperties.getStubWorkSleepMs()));
        m.put("app.eval.llm-real-enabled", Boolean.toString(appEvalProperties.isLlmRealEnabled()));
        m.put("app.eval.llm-real-timeout-ms", Long.toString(appEvalProperties.getLlmRealTimeoutMs()));
        m.put("app.eval.llm-real-require-tag-match", Boolean.toString(appEvalProperties.isLlmRealRequireTagMatch()));
        List<String> prefixSnap = new ArrayList<>(appEvalProperties.effectiveLlmRealRequiredTagPrefixes());
        Collections.sort(prefixSnap);
        m.put("app.eval.llm-real-required-tag-prefixes", String.join(",", prefixSnap));
        return m;
    }

    private static String canonicalConfigSnapshotString(LinkedHashMap<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (var e : map.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        return sb.toString();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void maybeStubWorkSleepForTests(String requestId) {
        long ms = appEvalProperties.getStubWorkSleepMs();
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("[eval] stub-work-sleep interrupted request_id={}", requestId);
        }
    }

    /**
     * 构造评测响应（不含 {@code latency_ms}，由 Controller 统一填入）。
     * <p>
     * 空 {@code query} 仍返回完整 {@code capabilities}，但<strong>不执行</strong>线性阶段（{@code stage_order=[]}，
     * {@code step_count=0}），且不执行 plan 解析；由 Controller 将 {@code behavior} 改为 {@code clarify}。
     */
    public EvalChatResponse buildStubResponse(EvalChatRequest request) {
        return buildStubResponse(request, null, EvalMembershipHttpContext.empty());
    }

    /**
     * @param xEvalMembershipTopN eval 下发的候选截断上限（见 P0+ §16）；为空则用服务端默认值。
     */
    public EvalChatResponse buildStubResponse(EvalChatRequest request, Integer xEvalMembershipTopN) {
        return buildStubResponse(request, xEvalMembershipTopN, EvalMembershipHttpContext.empty());
    }

    /**
     * @param membershipCtx eval 请求头派生的 membership 上下文；不全则跳过 hashed membership 字段。
     */
    public EvalChatResponse buildStubResponse(
            EvalChatRequest request,
            Integer xEvalMembershipTopN,
            EvalMembershipHttpContext membershipCtx
    ) {
        String mode = request.getMode();
        if (mode == null || mode.isBlank()) {
            mode = "EVAL";
        }
        String requestId = UUID.randomUUID().toString();

        boolean skipPipeline = request.getQuery() == null || request.getQuery().isBlank();

        EvalChatMeta meta = new EvalChatMeta(mode, requestId);
        meta.setReplanCount(EvalChatMeta.P0_REPLAN_COUNT);
        stampAgentTimeoutsOnMeta(meta);

        EvalCapabilities capabilities = new EvalCapabilities(
                new EvalRetrievalCapability(true, false),
                new EvalToolsCapability(true, true),
                new EvalStreamingCapability(false),
                new EvalGuardrailsCapability(false, false, false)
        );

        EvalChatResponse response = new EvalChatResponse();
        response.setCapabilities(capabilities);
        response.setMeta(meta);

        if (skipPipeline) {
            meta.setStageOrder(Collections.emptyList());
            meta.setStepCount(0);
            response.setAnswer("Day3：meta 可观测稳定（stage_order / step_count / replan_count=0）；阶段仍为占位执行。");
            response.setBehavior("answer");
            return complete(response, request, membershipCtx, emptyEvidence());
        }

        maybeStubWorkSleepForTests(requestId);

        // P0+ S2：检索前 SafetyGate（normalize + 可归因 rule_id），命中则直接短路返回。
        Optional<EvalChatSafetyGate.Decision> gate = EvalChatSafetyGate.evaluate(request.getQuery());
        if (gate.isPresent()) {
            EvalChatSafetyGate.Decision d = gate.get();
            meta.setEvalSafetyRuleId(d.ruleId());
            meta.setLowConfidenceReasons(d.reasons());
            meta.setStageOrder(List.of("PLAN", "GUARD"));
            meta.setStepCount(2);
            if ("clarify".equalsIgnoreCase(d.behavior())) {
                meta.setLowConfidence(true);
            }
            response.setBehavior(d.behavior());
            if (d.errorCode() != null) {
                response.setErrorCode(d.errorCode());
            }
            response.setAnswer(d.answer());
            appendPolicyEvent(meta, "safety_gate", PolicyStageAnchor.PRE_PLAN.wireValue(), d.behavior(), d.ruleId(), d.errorCode());
            return complete(response, request, membershipCtx, emptyEvidence());
        }

        PlanParseCoordinator.Result parseResult = planParseCoordinator.parseWithOptionalRepair(request.getPlanRaw());
        meta.setPlanParseAttempts(parseResult.attempts());
        meta.setPlanParseOutcome(parseResult.outcome());

        if (parseResult.failed()) {
            meta.setStageOrder(Collections.emptyList());
            meta.setStepCount(0);
            response.setBehavior("clarify");
            response.setErrorCode("PARSE_ERROR");
            response.setAnswer("Plan JSON 解析失败（已尝试一次修复仍无效）。请提供符合附录 E 的 plan。");
            return complete(response, request, membershipCtx, emptyEvidence());
        }

        final PlanV1 planV1;
        try {
            planV1 = planParser.parse(parseResult.effectivePlanJson());
        } catch (PlanParseException e) {
            meta.setStageOrder(Collections.emptyList());
            meta.setStepCount(0);
            response.setBehavior("clarify");
            response.setErrorCode("PARSE_ERROR");
            response.setAnswer("Plan JSON 二次解析失败（内部错误）。请提供符合附录 E 的 plan。");
            return complete(response, request, membershipCtx, emptyEvidence());
        }
        PlanPhysicalStagePolicy.PhysicalStageFlags physical = PlanPhysicalStagePolicy.resolve(planV1);
        final String planJsonForCheckpoint = parseResult.effectivePlanJson();

        Integer checkpointResumeLastInclusiveIndex = null;
        EvalCheckpointRepository cpRepo = evalCheckpointRepository.getIfAvailable();
        String convId = request.getConversationId();
        String planHashNow = sha256Hex(planJsonForCheckpoint);
        EvalCheckpointRow matchedCheckpointRow = null;
        if (appEvalProperties.isCheckpointPersistenceEnabled() && cpRepo != null
                && convId != null && !convId.isBlank() && convId.length() <= 128) {
            try {
                Optional<EvalCheckpointRow> rowOpt = cpRepo.findByConversationId(convId);
                if (rowOpt.isPresent()) {
                    EvalCheckpointRow row = rowOpt.get();
                    if (!planHashNow.equals(row.planRawSha256().trim())) {
                        meta.setStageOrder(List.of("PLAN"));
                        meta.setStepCount(1);
                        response.setBehavior("clarify");
                        response.setErrorCode(ERROR_CODE_EVAL_CHECKPOINT_PLAN_MISMATCH);
                        response.setAnswer("会话断点中的 plan 指纹与本次请求的 effective plan 不一致。请使用相同 plan_raw，或更换 conversation_id 开始新会话。");
                        meta.setEvalCheckpointOutcome("plan_mismatch");
                        appendPolicyEvent(meta, "eval_checkpoint", PolicyStageAnchor.POST_PLAN.wireValue(), "clarify", "plan_hash_mismatch",
                                ERROR_CODE_EVAL_CHECKPOINT_PLAN_MISMATCH);
                        return complete(response, request, membershipCtx, emptyEvidence(), planJsonForCheckpoint);
                    }
                    List<String> fullProj = EvalLinearAgentPipeline.projectedFullStageOrder(physical);
                    int lastIdx = fullProj.lastIndexOf(row.lastCompletedStage());
                    if (!row.resumeEligible() || lastIdx < 0) {
                        meta.setEvalCheckpointOutcome("matched_non_resumable");
                    } else if (lastIdx >= fullProj.size() - 1) {
                        meta.setStageOrder(fullProj);
                        meta.setStepCount(fullProj.size());
                        response.setBehavior("clarify");
                        response.setErrorCode(ERROR_CODE_EVAL_CHECKPOINT_RESUMED_EXHAUSTED);
                        response.setAnswer("断点显示该会话在当前 plan 下流水线已走完，无需续跑。");
                        meta.setEvalCheckpointOutcome("resume_exhausted");
                        return complete(response, request, membershipCtx, emptyEvidence(), planJsonForCheckpoint);
                    } else {
                        checkpointResumeLastInclusiveIndex = lastIdx;
                        meta.setEvalCheckpointOutcome("resumed");
                    }
                    matchedCheckpointRow = row;
                }
            } catch (Exception e) {
                log.warn("[eval] checkpoint_read_failed conversationId={} err={}", convId, e.toString());
            }
        }

        // Day10/S1：仅在 plan 声明 RETRIEVE 时拉取向量证据（与主线按 steps 物理跳过对齐）。
        // checkpoint 命中且 detail 中有同 query 的证据快照时，直接复用（避免重复向量检索）。
        Evidence evidence = emptyEvidence();
        if (physical.runRetrieve()) {
            ReuseAttempt<Evidence> reuse = tryReuseEvidenceFromCheckpoint(matchedCheckpointRow, request);
            if (reuse.reused() != null) {
                evidence = reuse.reused();
                meta.setCheckpointEvidenceReused(true);
                appendPolicyEvent(meta, "eval_checkpoint", PolicyStageAnchor.POST_PLAN.wireValue(),
                        "answer", "reuse_hit:evidence", null);
            } else {
                if (reuse.missReason() != null) {
                    meta.setCheckpointEvidenceReuseMissReason(reuse.missReason());
                    appendPolicyEvent(meta, "eval_checkpoint", PolicyStageAnchor.POST_PLAN.wireValue(),
                            "answer", "reuse_miss:evidence:" + reuse.missReason(), null);
                }
                evidence = retrieveEvidence(request.getQuery(), mode, xEvalMembershipTopN);
            }
        }
        if (!evidence.retrievalHits.isEmpty()) {
            response.setRetrievalHits(evidence.retrievalHits);
            meta.setRetrieveHitCount(evidence.retrievalHits.size());
        }
        if (!evidence.sources.isEmpty()) {
            response.setSources(evidence.sources);
        }

        // 续跑且已从断点复用检索快照时，不再用同一套「检索后」确定性门控卡第二次请求（否则会永远停在 RETRIEVE）。
        final boolean skipDuplicateRetrieveGates =
                checkpointResumeLastInclusiveIndex != null
                        && Boolean.TRUE.equals(meta.getCheckpointEvidenceReused());

        Optional<EvalQuerySafetyPolicy.Decision> safety = EvalQuerySafetyPolicy.evaluate(request.getQuery());
        if (safety.isPresent()) {
            EvalQuerySafetyPolicy.Decision d = safety.get();
            meta.setStageOrder(List.of("PLAN", "GUARD"));
            meta.setStepCount(2);
            meta.setLowConfidenceReasons(d.reasons());
            response.setBehavior(d.behavior());
            if (d.errorCode() != null) {
                response.setErrorCode(d.errorCode());
            }
            response.setAnswer(d.answer());
            appendPolicyEvent(meta, "query_safety", PolicyStageAnchor.POST_PLAN.wireValue(), d.behavior(), firstPolicyRuleFromReasons(d.reasons()), d.errorCode());
            return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
        }

        // P0+ S2：确定性行为策略（tool/clarify），用于收敛典型 BEHAVIOR_MISMATCH。
        if ("EVAL".equalsIgnoreCase(mode)) {
            Optional<EvalBehaviorPolicy.Decision> d = EvalBehaviorPolicy.evaluateForEvalMode(request.getQuery());
            if (d.isPresent()) {
                EvalBehaviorPolicy.Decision decision = d.get();
                response.setBehavior(decision.behavior());
                if (decision.errorCode() != null && !decision.errorCode().isBlank()) {
                    response.setErrorCode(decision.errorCode());
                }
                if (decision.reasons() != null && !decision.reasons().isEmpty()) {
                    meta.setLowConfidenceReasons(decision.reasons());
                }
                appendPolicyEvent(meta, "behavior_policy", PolicyStageAnchor.POST_RETRIEVE.wireValue(), decision.behavior(),
                        firstPolicyRuleFromReasons(decision.reasons()), decision.errorCode());
                if ("clarify".equalsIgnoreCase(decision.behavior())) {
                    meta.setLowConfidence(true);
                    meta.setStageOrder(List.of("PLAN", "RETRIEVE"));
                    meta.setStepCount(2);
                } else if ("tool".equalsIgnoreCase(decision.behavior())) {
                    // 与默认线性流水线保持一致，避免 eval 对 meta.stage_order 的隐含期望造成 tool_succeeded=false。
                    meta.setStageOrder(List.of("PLAN", "RETRIEVE", "TOOL", "GUARD", "WRITE"));
                    meta.setStepCount(5);
                    EvalChatResultTool toolDto = new EvalChatResultTool();
                    toolDto.setRequired(true);
                    toolDto.setUsed(true);
                    toolDto.setSucceeded(true);
                    toolDto.setName(EvalToolStageRunner.STUB_TOOL_NAME);
                    toolDto.setOutcome(EvalToolStageRunner.OUTCOME_OK);
                    response.setTool(toolDto);
                    meta.setToolCallsCount(1);
                    meta.setToolOutcome(EvalToolStageRunner.OUTCOME_OK);
                }
                response.setAnswer(decision.answer());
                return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
            }
        }

        if (!skipDuplicateRetrieveGates) {
            // 业务侧“空命中”兜底门控（不依赖 eval_rag_scenario）；避免在无证据时强答/编造引用。
            if (physical.runRetrieve() && meta.getRetrieveHitCount() != null && meta.getRetrieveHitCount() == 0) {
                meta.setStageOrder(List.of("PLAN", "RETRIEVE"));
                meta.setStepCount(2);
                meta.setLowConfidence(true);
                meta.setLowConfidenceReasons(EvalRagGateScenarios.REASONS_EMPTY_HITS);
                response.setBehavior("clarify");
                response.setErrorCode(EvalRagGateScenarios.ERROR_CODE_RETRIEVE_EMPTY);
                response.setAnswer("检索零命中：请补充关键词/范围/上下文，或提供可引用资料后再继续。");
                appendPolicyEvent(meta, "rag_gate", PolicyStageAnchor.POST_RETRIEVE.wireValue(), "clarify", "business_empty_hits",
                        EvalRagGateScenarios.ERROR_CODE_RETRIEVE_EMPTY);
                return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
            }

            // 业务侧“低置信”兜底门控：短 query / 指代不明 等，避免同类输入漂移到 answer。
            String q = request.getQuery().trim();
            if (physical.runRetrieve()
                    && (q.length() <= 6 || q.contains("这个东西") || q.contains("那个项目") || q.contains("那个") && q.contains("项目"))) {
                meta.setStageOrder(List.of("PLAN", "RETRIEVE"));
                meta.setStepCount(2);
                meta.setLowConfidence(true);
                meta.setLowConfidenceReasons(EvalRagGateScenarios.REASONS_LOW_CONFIDENCE_BUSINESS);
                response.setBehavior("clarify");
                response.setErrorCode(EvalRagGateScenarios.ERROR_CODE_RETRIEVE_LOW_CONFIDENCE);
                response.setAnswer("信息不足或指代不明：请补充目的地/日期/偏好，或说明你指的是哪个对象/项目。");
                appendPolicyEvent(meta, "rag_gate", PolicyStageAnchor.POST_RETRIEVE.wireValue(), "clarify", "business_low_confidence_heuristic",
                        EvalRagGateScenarios.ERROR_CODE_RETRIEVE_LOW_CONFIDENCE);
                return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
            }

            Kind ragKind = physical.runRetrieve() ? EvalRagGateScenarios.resolve(request) : null;
            if (ragKind != null) {
                meta.setStageOrder(List.of("PLAN", "RETRIEVE"));
                meta.setStepCount(2);
                meta.setLowConfidence(true);
                if (ragKind == Kind.EMPTY_HITS) {
                    meta.setRetrieveHitCount(0);
                    meta.setLowConfidenceReasons(EvalRagGateScenarios.REASONS_EMPTY_HITS);
                    response.setErrorCode(EvalRagGateScenarios.ERROR_CODE_RETRIEVE_EMPTY);
                    response.setAnswer("检索零命中，已门控为澄清（评测 stub，P0 未启用 score 阈值）。");
                    appendPolicyEvent(meta, "rag_gate", PolicyStageAnchor.POST_RETRIEVE.wireValue(), "clarify", "stub_empty_hits",
                            EvalRagGateScenarios.ERROR_CODE_RETRIEVE_EMPTY);
                } else {
                    meta.setRetrieveHitCount(1);
                    meta.setLowConfidenceReasons(EvalRagGateScenarios.REASONS_LOW_CONFIDENCE);
                    response.setAnswer("证据置信不足，已门控为澄清（评测 stub，P0 未启用 score 阈值）。");
                    appendPolicyEvent(meta, "rag_gate", PolicyStageAnchor.POST_RETRIEVE.wireValue(), "clarify", "stub_low_confidence",
                            EvalRagGateScenarios.ERROR_CODE_RETRIEVE_LOW_CONFIDENCE);
                }
                response.setBehavior("clarify");
                return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
            }
        }

        final EvalCheckpointRow checkpointRowForToolReuse = matchedCheckpointRow;
        AtomicReference<EvalToolStageRunner.EvalToolInvocationResult> toolSlot = new AtomicReference<>();
        int resumeIdx = checkpointResumeLastInclusiveIndex == null ? -1 : checkpointResumeLastInclusiveIndex;
        List<String> stageOrder = EvalLinearAgentPipeline.runStubStagesResumingAfterIndex(request, physical, resumeIdx, () -> {
            if (toolSlot.get() != null) {
                return;
            }
            ReuseAttempt<EvalToolStageRunner.EvalToolInvocationResult> reuse = tryReuseToolFromCheckpoint(checkpointRowForToolReuse, request);
            if (reuse.reused() != null) {
                toolSlot.set(reuse.reused());
                meta.setCheckpointToolReused(true);
                appendPolicyEvent(meta, "eval_checkpoint", PolicyStageAnchor.TOOL.wireValue(),
                        "answer", "reuse_hit:tool", null);
                return;
            }
            if (reuse.missReason() != null) {
                meta.setCheckpointToolReuseMissReason(reuse.missReason());
                appendPolicyEvent(meta, "eval_checkpoint", PolicyStageAnchor.TOOL.wireValue(),
                        "answer", "reuse_miss:tool:" + reuse.missReason(), null);
            }
            EvalToolStageRunner.EvalToolInvocationResult r = evalToolStageRunner.invoke(request);
            if (r != null) {
                toolSlot.set(r);
            }
        });
        meta.setStageOrder(stageOrder);
        meta.setStepCount(stageOrder.size());

        EvalToolStageRunner.EvalToolInvocationResult inv = toolSlot.get();
        if (inv != null) {
            EvalChatResultTool toolDto = new EvalChatResultTool();
            toolDto.setRequired(true);
            toolDto.setUsed(inv.used());
            toolDto.setName(inv.name());
            toolDto.setOutcome(inv.outcome());
            toolDto.setSucceeded(EvalToolStageRunner.OUTCOME_OK.equals(inv.outcome()));
            response.setTool(toolDto);
            meta.setToolCallsCount(inv.used() ? 1 : 0);
            meta.setToolOutcome(inv.outcome());
            meta.setToolLatencyMs(inv.latencyMs());
            // meta 上的布尔字段采用“只写 true”的策略：
            // - 避免输出大量无意义的 false（减少 JSON 噪音）
            // - 统计上更容易理解：字段出现即表示触发
            if (Boolean.TRUE.equals(inv.toolDisabledByCircuitBreaker())) {
                meta.setToolDisabledByCircuitBreaker(true);
            }
            if (Boolean.TRUE.equals(inv.toolRateLimited())) {
                meta.setToolRateLimited(true);
            }
            if (Boolean.TRUE.equals(inv.toolOutputTruncated())) {
                meta.setToolOutputTruncated(true);
            }
            switch (inv.kind()) {
                case OK -> {
                    response.setBehavior("tool");
                    response.setAnswer("评测：工具阶段成功（stub）。");
                }
                case TIMEOUT -> {
                    response.setBehavior("answer");
                    response.setErrorCode(EvalToolStageRunner.ERROR_CODE_TOOL_TIMEOUT);
                    response.setAnswer("工具阶段超时，已降级返回（评测 stub）。");
                }
                case ERROR -> {
                    response.setBehavior("answer");
                    response.setErrorCode(EvalToolStageRunner.ERROR_CODE_TOOL_ERROR);
                    response.setAnswer("工具调用失败，已降级返回（评测 stub）。");
                }
                case CIRCUIT_BREAKER -> {
                    response.setBehavior("answer");
                    response.setErrorCode(EvalToolStageRunner.ERROR_CODE_TOOL_DISABLED_BY_CIRCUIT_BREAKER);
                    response.setAnswer("工具因熔断暂不可用，已降级返回（评测 stub）。");
                }
                case RATE_LIMITED -> {
                    response.setBehavior("answer");
                    response.setErrorCode(EvalToolStageRunner.ERROR_CODE_RATE_LIMITED);
                    response.setAnswer("工具触发限流，已降级返回（评测 stub）。");
                }
            }
            appendPolicyEvent(meta, "tool_stage", PolicyStageAnchor.TOOL.wireValue(), response.getBehavior(), inv.outcome(), response.getErrorCode());
            return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
        }

        response.setAnswer("Day3：meta 可观测稳定（stage_order / step_count / replan_count=0）；阶段仍为占位执行。");
        response.setBehavior("answer");
        return complete(response, request, membershipCtx, evidence, planJsonForCheckpoint);
    }

    private static String firstPolicyRuleFromReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }
        return reasons.stream()
                .filter(Objects::nonNull)
                .filter(r -> r.startsWith("safety:"))
                .findFirst()
                .orElseGet(() -> reasons.get(0));
    }

    private void appendPolicyEvent(
            EvalChatMeta meta,
            String policyType,
            String stage,
            String behavior,
            String ruleId,
            String errorCode
    ) {
        appendPolicyEvent(meta, policyType, stage, behavior, ruleId, errorCode, null, null, null);
    }

    private void appendPolicyEvent(
            EvalChatMeta meta,
            String policyType,
            String stage,
            String behavior,
            String ruleId,
            String errorCode,
            String decision,
            String severity,
            Map<String, String> attrs
    ) {
        if (meta == null || policyType == null || policyType.isBlank()
                || stage == null || stage.isBlank()
                || behavior == null || behavior.isBlank()) {
            return;
        }
        PolicyEvent shared = PolicyEvent.of(policyType, stage, behavior, ruleId, errorCode, meta.getRequestId());
        List<EvalChatPolicyEvent> list = meta.getPolicyEvents();
        if (list == null) {
            list = new ArrayList<>();
            meta.setPolicyEvents(list);
        }
        EvalChatPolicyEvent e = new EvalChatPolicyEvent();
        e.setPolicyType(shared.policyType());
        e.setStage(shared.stage());
        e.setBehavior(shared.behavior());
        if (shared.ruleId() != null && !shared.ruleId().isBlank()) {
            e.setRuleId(shared.ruleId());
        }
        if (shared.errorCode() != null && !shared.errorCode().isBlank()) {
            e.setErrorCode(shared.errorCode());
        }
        if (decision != null && !decision.isBlank()) {
            e.setDecision(decision);
        }
        if (severity != null && !severity.isBlank()) {
            e.setSeverity(severity);
        }
        if (attrs != null && !attrs.isEmpty()) {
            e.setAttrs(Collections.unmodifiableMap(new LinkedHashMap<>(attrs)));
        }
        list.add(e);
    }

    private EvalChatResponse complete(
            EvalChatResponse response,
            EvalChatRequest request,
            EvalMembershipHttpContext membershipCtx,
            Evidence evidence
    ) {
        return complete(response, request, membershipCtx, evidence, null);
    }

    private EvalChatResponse complete(
            EvalChatResponse response,
            EvalChatRequest request,
            EvalMembershipHttpContext membershipCtx,
            Evidence evidence,
            String effectivePlanJsonForCheckpoint
    ) {
        stampContextTruncationOnMeta(response.getMeta(), evidence);
        stampContextSizeEstimatesOnMeta(response.getMeta(), request, evidence);
        maybeAttachProviderTokenUsage(response.getMeta(), request);
        EvalReflectionSupport.apply(response, request, appEvalProperties.isReflectionMetaEnabled());
        attachRetrievalMembershipMeta(response.getMeta(), response, membershipCtx, evidence);
        maybeAttachSyntheticStageTrace(response);
        maybeAttachMarketDataEvalContract(response, request);
        maybePersistEvalCheckpoint(request, response, effectivePlanJsonForCheckpoint, evidence);
        return response;
    }

    private void maybeAttachSyntheticStageTrace(EvalChatResponse response) {
        EvalChatMeta meta = response != null ? response.getMeta() : null;
        if (meta == null || meta.getStageTrace() != null) {
            return;
        }
        List<String> stageOrder = meta.getStageOrder();
        if (stageOrder == null || stageOrder.isEmpty()) {
            return;
        }
        List<String> normalized = stageOrder.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(s -> s.toUpperCase(java.util.Locale.ROOT))
                .toList();
        if (normalized.isEmpty()) {
            return;
        }
        Set<String> executed = Set.copyOf(normalized);
        List<String> traceOrder = syntheticStageTraceOrder(normalized);
        if (traceOrder.isEmpty()) {
            return;
        }
        List<com.travel.ai.eval.dto.EvalChatStageTrace> traces = new ArrayList<>(traceOrder.size());
        for (String stage : traceOrder) {
            boolean stageExecuted = executed.contains(stage);
            NodeStatus status = syntheticStageStatus(stage, stageExecuted, response);
            String errorCode = syntheticStageErrorCode(stage, status, response);
            Map<String, String> attrs = stageExecuted ? Map.of() : Map.of("reason", "skipped_by_plan");
            StageTrace trace = new StageTrace(stage, status, 0L, errorCode, null, attrs);
            traces.add(RuntimeEvalTraceMapper.toEvalStageTrace(trace));
        }
        meta.setStageTrace(traces);
    }

    private static List<String> syntheticStageTraceOrder(List<String> normalizedStageOrder) {
        String last = normalizedStageOrder.get(normalizedStageOrder.size() - 1);
        int lastFixedIndex = EVAL_STAGE_TRACE_ORDER.indexOf(last);
        if (lastFixedIndex < 0) {
            return normalizedStageOrder;
        }
        return EVAL_STAGE_TRACE_ORDER.subList(0, lastFixedIndex + 1);
    }

    private static NodeStatus syntheticStageStatus(String stage, boolean stageExecuted, EvalChatResponse response) {
        if (!stageExecuted) {
            return NodeStatus.SKIPPED;
        }
        if (!"TOOL".equals(stage)) {
            return NodeStatus.SUCCESS;
        }
        EvalChatResultTool tool = response != null ? response.getTool() : null;
        if (tool == null || tool.getOutcome() == null) {
            return NodeStatus.SUCCESS;
        }
        return switch (tool.getOutcome()) {
            case EvalToolStageRunner.OUTCOME_TIMEOUT -> NodeStatus.TIMEOUT;
            case EvalToolStageRunner.OUTCOME_ERROR,
                    EvalToolStageRunner.OUTCOME_DISABLED_BY_CIRCUIT_BREAKER,
                    EvalToolStageRunner.OUTCOME_RATE_LIMITED -> NodeStatus.FAILED;
            default -> NodeStatus.SUCCESS;
        };
    }

    private static String syntheticStageErrorCode(String stage, NodeStatus status, EvalChatResponse response) {
        if (!"TOOL".equals(stage) || status == NodeStatus.SUCCESS || status == NodeStatus.SKIPPED) {
            return null;
        }
        String errorCode = response != null ? response.getErrorCode() : null;
        return errorCode != null && !errorCode.isBlank() ? errorCode : null;
    }

    private void maybeAttachMarketDataEvalContract(EvalChatResponse response, EvalChatRequest request) {
        if (response == null || response.getMeta() == null || request == null
                || !isMarketDataExplainQuery(request.getQuery())) {
            return;
        }
        EvalChatMeta meta = response.getMeta();
        meta.setContractVersion(EVAL_CONTRACT_V1);
        meta.setWorkflowId(WORKFLOW_MARKET_DATA_EXPLAIN);
        meta.setWorkflowVersion(WORKFLOW_MARKET_DATA_VERSION);
        meta.setWorkflowFamily(WORKFLOW_FAMILY_FINANCE);

        appendPolicyEvent(
                meta,
                FINANCE_GUARD_POLICY_TYPE,
                "guard",
                response.getBehavior(),
                FINANCE_GUARD_RULE_ID,
                null,
                "allow",
                "info",
                marketDataFinanceGuardAttrs()
        );
        maybeAttachMarketDataToolTrace(response);
    }

    private void maybeAttachMarketDataToolTrace(EvalChatResponse response) {
        EvalChatResultTool tool = response.getTool();
        EvalChatMeta meta = response.getMeta();
        if (tool == null || !Boolean.TRUE.equals(tool.getRequired()) || !tool.isUsed()) {
            return;
        }
        ToolTrace trace = new ToolTrace(
                MARKET_DATA_CONNECTOR,
                MARKET_DATA_CONNECTOR,
                true,
                true,
                Boolean.TRUE.equals(tool.getSucceeded()),
                tool.getOutcome(),
                meta.getToolLatencyMs(),
                response.getErrorCode(),
                null,
                null,
                marketDataToolTraceAttrs()
        );
        meta.setToolTrace(List.of(RuntimeEvalTraceMapper.toEvalToolTrace(trace)));
    }

    private static boolean isMarketDataExplainQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String q = query.toLowerCase(java.util.Locale.ROOT);
        return q.contains("market")
                || q.contains("quote")
                || q.contains("price")
                || q.contains("ticker")
                || q.contains("volume")
                || q.contains("p/e")
                || q.contains("pe ratio")
                || q.contains("valuation")
                || q.contains("aapl");
    }

    private static Map<String, String> marketDataFinanceGuardAttrs() {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        attrs.put("workflow_id", WORKFLOW_MARKET_DATA_EXPLAIN);
        attrs.put("connector", MARKET_DATA_CONNECTOR);
        attrs.put("mock_mode", "true");
        attrs.put("freshness", "mock_non_realtime");
        attrs.put("tradable", "false");
        attrs.put("disclosure_required", "true");
        attrs.put("investment_advice_allowed", "false");
        return attrs;
    }

    private static Map<String, String> marketDataToolTraceAttrs() {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        attrs.put("mock_mode", "true");
        attrs.put("freshness", "mock_non_realtime");
        attrs.put("tradable", "false");
        return attrs;
    }

    private void maybePersistEvalCheckpoint(
            EvalChatRequest request,
            EvalChatResponse response,
            String effectivePlanJsonUtf8,
            Evidence evidence
    ) {
        if (!appEvalProperties.isCheckpointPersistenceEnabled()) {
            return;
        }
        EvalCheckpointRepository repo = evalCheckpointRepository.getIfAvailable();
        if (repo == null || request == null || response == null || response.getMeta() == null) {
            return;
        }
        String conv = request.getConversationId();
        if (conv == null || conv.isBlank()) {
            return;
        }
        if (conv.length() > 128) {
            log.warn("[eval] checkpoint_skipped conversation_id_too_long len={}", conv.length());
            return;
        }
        if (effectivePlanJsonUtf8 == null || effectivePlanJsonUtf8.isBlank()) {
            return;
        }
        if (ERROR_CODE_EVAL_CHECKPOINT_PLAN_MISMATCH.equals(response.getErrorCode())) {
            // 指纹不一致时不再覆盖断点行，避免把「旧进度」冲掉。
            return;
        }
        EvalChatMeta meta = response.getMeta();
        List<String> order = meta.getStageOrder();
        if (order == null || order.isEmpty()) {
            return;
        }
        String planHash = sha256Hex(effectivePlanJsonUtf8);
        if (planHash.length() != 64) {
            return;
        }
        String lastStage = order.get(order.size() - 1);
        if (lastStage == null || lastStage.isBlank()) {
            return;
        }
        if (lastStage.length() > 32) {
            log.warn("[eval] checkpoint_skipped last_stage_too_long");
            return;
        }
        int stageIndex = Math.min(order.size() - 1, 32);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("request_id", meta.getRequestId());
        if (request.getQuery() != null && !request.getQuery().isBlank()) {
            detail.put("query_sha256", sha256Hex(request.getQuery().trim()));
        }
        if (response.getBehavior() != null) {
            detail.put("behavior", response.getBehavior());
        }
        if (response.getErrorCode() != null && !response.getErrorCode().isBlank()) {
            detail.put("error_code", response.getErrorCode());
        }
        boolean resumeEligible = false;
        try {
            PlanV1 pv = planParser.parse(effectivePlanJsonUtf8);
            PlanPhysicalStagePolicy.PhysicalStageFlags pf = PlanPhysicalStagePolicy.resolve(pv);
            List<String> projected = EvalLinearAgentPipeline.projectedFullStageOrder(pf);
            int idx = projected.lastIndexOf(lastStage);
            // resume_eligible 表示「last_completed_stage 能在投影 plan 中定位」，含末阶段。
            // 续跑读取侧（resolveCheckpointResume）再按 idx 区分：未到末阶段→续跑；已到末阶段→resume_exhausted。
            // 此处若排除末阶段（idx < size-1），会让 resume_exhausted 分支永不可达。
            resumeEligible = idx >= 0;
        } catch (Exception e) {
            log.debug("[eval] checkpoint_resume_eligible_parse_failed: {}", e.toString());
        }
        detail.put("resume_eligible", resumeEligible);
        // 始终写入列表键（可为空），便于续跑时按 query 哈希复用「零命中」等快照。
        if (evidence != null) {
            detail.put("evidence_retrieval_hits",
                    evidence.retrievalHits() != null ? evidence.retrievalHits() : List.of());
            detail.put("evidence_sources", evidence.sources() != null ? evidence.sources() : List.of());
        }
        if (evidence != null) {
            detail.put("evidence_candidate_total", evidence.candidateTotal());
            detail.put("evidence_candidate_limit_n", evidence.candidateLimitN());
            if (evidence.sourcesSnippetTruncated()) {
                detail.put("sources_snippet_truncated", true);
            }
            if (evidence.retrievalCandidatesCapped()) {
                detail.put("retrieval_candidates_capped", true);
            }
            if (evidence.retrievalQueryLineTruncated()) {
                detail.put("retrieval_query_line_truncated", true);
            }
        }
        // 工具快照：用于在同一 query + eval_tool_scenario 下复用 TOOL 结果（避免重复 stub/外部调用）。
        if (request.getEvalToolScenario() != null && !request.getEvalToolScenario().isBlank()) {
            detail.put("eval_tool_scenario", request.getEvalToolScenario().trim());
        }
        if (response.getTool() != null) {
            detail.put("tool_used", response.getTool().isUsed());
            detail.put("tool_name", response.getTool().getName());
            detail.put("tool_outcome", response.getTool().getOutcome());
            detail.put("tool_succeeded", response.getTool().getSucceeded());
            if (meta.getToolLatencyMs() != null) {
                detail.put("tool_latency_ms", meta.getToolLatencyMs());
            }
            if (Boolean.TRUE.equals(meta.getToolDisabledByCircuitBreaker())) {
                detail.put("tool_disabled_by_circuit_breaker", true);
            }
            if (Boolean.TRUE.equals(meta.getToolRateLimited())) {
                detail.put("tool_rate_limited", true);
            }
            if (Boolean.TRUE.equals(meta.getToolOutputTruncated())) {
                detail.put("tool_output_truncated", true);
            }
        }
        try {
            repo.upsert(
                    conv,
                    planHash,
                    lastStage,
                    stageIndex,
                    meta.getConfigSnapshotHash(),
                    detail
            );
        } catch (Exception e) {
            log.warn("[eval] checkpoint_persist_failed conversationId={} err={}", conv, e.toString());
        }
    }

    private void maybeAttachProviderTokenUsage(EvalChatMeta meta, EvalChatRequest request) {
        if (meta == null || request == null) {
            return;
        }
        String mode = request.getLlmMode();
        boolean wantReal = mode != null && mode.trim().equalsIgnoreCase("real");
        if (!wantReal || !appEvalProperties.isLlmRealEnabled()) {
            return;
        }
        String tagFail = EvalLlmRealTagPolicy.tagGateFailureReasonOrNull(
                appEvalProperties.isLlmRealRequireTagMatch(),
                appEvalProperties.effectiveLlmRealRequiredTagPrefixes(),
                request.getEvalTags());
        if (tagFail != null) {
            meta.setProviderUsageAvailable(false);
            meta.setProviderUsageFailureReason(tagFail);
            return;
        }
        ChatClient client = evalUsageChatClient.getIfAvailable();
        if (client == null) {
            meta.setProviderUsageAvailable(false);
            meta.setProviderUsageFailureReason("no_client");
            return;
        }
        long timeoutMs = Math.max(200L, appEvalProperties.getLlmRealTimeoutMs());
        var es = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "eval-llm-usage-probe");
            t.setDaemon(true);
            return t;
        });
        try {
            Future<Optional<LlmUsage>> f = es.submit(() -> {
                // 触发一次真实调用（回答内容不作为评测输出）；usage 以 provider/SDK 暴露为准
                Object callSpec = client.prompt()
                        .user(request.getQuery() == null ? "" : request.getQuery())
                        .call();

                // 优先尝试从 callSpec 本体提取；若无则反射调用 chatResponse()
                Optional<LlmUsage> u = SpringAiUsageExtractor.tryExtract(callSpec);
                if (u.isEmpty()) {
                    u = tryExtractFromCallSpecChatResponse(callSpec);
                }
                return u;
            });
            Optional<LlmUsage> u = f.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (u.isEmpty()) {
                meta.setProviderUsageAvailable(false);
                meta.setProviderUsageFailureReason("no_usage");
                return;
            }
            LlmUsage usage = u.get();
            meta.setProviderUsageAvailable(true);
            meta.setTokenSource("provider");
            meta.setPromptTokens(usage.promptTokens());
            meta.setCompletionTokens(usage.completionTokens());
            meta.setTotalTokens(usage.totalTokens());
        } catch (TimeoutException e) {
            meta.setProviderUsageAvailable(false);
            meta.setProviderUsageFailureReason("timeout");
        } catch (Exception e) {
            meta.setProviderUsageAvailable(false);
            meta.setProviderUsageFailureReason("error");
        } finally {
            es.shutdownNow();
        }
    }

    private static Optional<LlmUsage> tryExtractFromCallSpecChatResponse(Object callSpec) {
        if (callSpec == null) {
            return Optional.empty();
        }
        try {
            var m = callSpec.getClass().getMethod("chatResponse");
            Object resp = m.invoke(callSpec);
            return SpringAiUsageExtractor.tryExtract(resp);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void stampContextTruncationOnMeta(EvalChatMeta meta, Evidence evidence) {
        if (meta == null || evidence == null) {
            return;
        }
        List<String> reasons = new ArrayList<>();
        if (evidence.sourcesSnippetTruncated()) {
            reasons.add("sources_snippet_truncated");
        }
        if (Boolean.TRUE.equals(meta.getToolOutputTruncated())) {
            reasons.add("tool_output_truncated");
        }
        if (evidence.retrievalCandidatesCapped()) {
            reasons.add("retrieval_candidates_capped");
        }
        if (evidence.retrievalQueryLineTruncated()) {
            reasons.add("retrieval_query_line_truncated");
        }
        if (!reasons.isEmpty()) {
            meta.setContextTruncated(true);
            meta.setContextTruncationReasons(Collections.unmodifiableList(reasons));
        }
    }

    private static void stampContextSizeEstimatesOnMeta(EvalChatMeta meta, EvalChatRequest request, Evidence evidence) {
        if (meta == null) {
            return;
        }
        int queryChars = 0;
        if (request != null && request.getQuery() != null) {
            queryChars = request.getQuery().length();
        }

        int sourcesChars = 0;
        if (evidence != null && evidence.sources() != null) {
            for (EvalChatSource s : evidence.sources()) {
                if (s != null && s.getSnippet() != null) {
                    sourcesChars += s.getSnippet().length();
                }
            }
        }

        int total = queryChars + sourcesChars;
        // 经验系数：4 chars ≈ 1 token（只做趋势/异常检测，不做精确计费口径）
        int charsPerToken = 4;
        int tokenEst = (int) Math.ceil(total / (double) charsPerToken);

        meta.setContextQueryCharCount(queryChars);
        meta.setContextSourcesSnippetCharCount(sourcesChars);
        meta.setContextCharCount(total);
        meta.setContextTokenEstimate(tokenEst);
        meta.setContextBudgetSourcesSnippetMaxChars(300);
        meta.setContextBudgetCharsPerTokenEstimate(charsPerToken);
        // P1-0 组合策略：默认以 estimate 为准；若后续 real LLM 调用拿到 provider usage，会覆盖为 provider。
        if (meta.getTokenSource() == null) {
            meta.setTokenSource("estimate");
        }
    }

    private static void attachRetrievalMembershipMeta(
            EvalChatMeta meta,
            EvalChatResponse response,
            EvalMembershipHttpContext ctx,
            Evidence evidence
    ) {
        if (meta == null || !ctx.completeForHashedMembership()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        List<EvalChatRetrievalHit> hits = response.getRetrievalHits();
        if (hits != null && !hits.isEmpty()) {
            for (EvalChatRetrievalHit h : hits) {
                if (h.getId() != null && !h.getId().isBlank()) {
                    ids.add(RetrievalMembershipHasher.canonicalChunkId(h.getId()));
                }
            }
        }
        // 与 vagent 对齐：若根级 retrieval_hits 未挂上（历史路径/序列化差异），仍可从 sources[*].id 派生 hashes
        if (ids.isEmpty()) {
            List<EvalChatSource> srcs = response.getSources();
            if (srcs == null || srcs.isEmpty()) {
                return;
            }
            for (EvalChatSource s : srcs) {
                if (s.getId() != null && !s.getId().isBlank()) {
                    ids.add(RetrievalMembershipHasher.canonicalChunkId(s.getId()));
                }
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        List<String> hashes = RetrievalMembershipHasher.sortedHitIdHashes(
                ctx.token(), ctx.targetId(), ctx.datasetId(), ctx.caseId(), ids);
        if (hashes.isEmpty()) {
            return;
        }
        meta.setRetrievalHitIdHashes(hashes);
        meta.setRetrievalHitIdHashAlg("HMAC-SHA256");
        meta.setRetrievalHitIdHashKeyDerivation("x-eval-token/v1");
        meta.setRetrievalCandidateLimitN(evidence.candidateLimitN());
        meta.setRetrievalCandidateTotal(evidence.candidateTotal());
        meta.setCanonicalHitIdScheme("kb_chunk_id");
    }

    private record Evidence(
            List<EvalChatRetrievalHit> retrievalHits,
            List<EvalChatSource> sources,
            int candidateTotal,
            int candidateLimitN,
            boolean sourcesSnippetTruncated,
            boolean retrievalCandidatesCapped,
            boolean retrievalQueryLineTruncated
    ) {
    }

    private record ReuseAttempt<T>(T reused, String missReason) {
    }

    private static Evidence emptyEvidence() {
        return new Evidence(List.of(), List.of(), 0, 0, false, false, false);
    }

    @SuppressWarnings("unchecked")
    private ReuseAttempt<Evidence> tryReuseEvidenceFromCheckpoint(EvalCheckpointRow row, EvalChatRequest request) {
        if (row == null || request == null) {
            return new ReuseAttempt<>(null, "no_checkpoint");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return new ReuseAttempt<>(null, null);
        }
        Map<String, Object> d = row.detail();
        if (d == null || d.isEmpty()) {
            return new ReuseAttempt<>(null, "snapshot_missing");
        }
        Object qh = d.get("query_sha256");
        if (!(qh instanceof String storedHash) || storedHash.isBlank()) {
            return new ReuseAttempt<>(null, "checkpoint_missing_query_hash");
        }
        String now = sha256Hex(request.getQuery().trim());
        if (!storedHash.trim().equalsIgnoreCase(now)) {
            return new ReuseAttempt<>(null, "query_hash_mismatch");
        }
        Object hitsRaw = d.get("evidence_retrieval_hits");
        Object sourcesRaw = d.get("evidence_sources");
        if (!(hitsRaw instanceof List<?> hitsList) || !(sourcesRaw instanceof List<?> sourcesList)) {
            return new ReuseAttempt<>(null, "snapshot_missing");
        }
        List<EvalChatRetrievalHit> hits = new ArrayList<>(hitsList.size());
        for (Object o : hitsList) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                Object title = m.get("title");
                Object score = m.get("score");
                EvalChatRetrievalHit h = new EvalChatRetrievalHit();
                if (id != null) h.setId(String.valueOf(id));
                if (title != null) h.setTitle(String.valueOf(title));
                if (score instanceof Number n) h.setScore(n.doubleValue());
                hits.add(h);
            }
        }
        List<EvalChatSource> sources = new ArrayList<>(sourcesList.size());
        for (Object o : sourcesList) {
            if (o instanceof Map<?, ?> m) {
                Object id = m.get("id");
                Object title = m.get("title");
                Object snippet = m.get("snippet");
                Object score = m.get("score");
                EvalChatSource s = new EvalChatSource();
                if (id != null) s.setId(String.valueOf(id));
                if (title != null) s.setTitle(String.valueOf(title));
                if (snippet != null) s.setSnippet(String.valueOf(snippet));
                if (score instanceof Number n) s.setScore(n.doubleValue());
                sources.add(s);
            }
        }
        int candidateTotal = intFromDetail(d.get("evidence_candidate_total"));
        int limitN = intFromDetail(d.get("evidence_candidate_limit_n"));
        boolean snippetTruncated = boolFromDetail(d.get("sources_snippet_truncated"));
        boolean capped = boolFromDetail(d.get("retrieval_candidates_capped"));
        boolean queryLineTruncated = boolFromDetail(d.get("retrieval_query_line_truncated"));
        return new ReuseAttempt<>(new Evidence(
                Collections.unmodifiableList(hits),
                Collections.unmodifiableList(sources),
                candidateTotal,
                limitN,
                snippetTruncated,
                capped,
                queryLineTruncated
        ), null);
    }

    private static int intFromDetail(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean boolFromDetail(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof String s) {
            return "true".equalsIgnoreCase(s.trim()) || "1".equals(s.trim());
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        return false;
    }

    private ReuseAttempt<EvalToolStageRunner.EvalToolInvocationResult> tryReuseToolFromCheckpoint(EvalCheckpointRow row, EvalChatRequest request) {
        if (row == null || request == null) {
            return new ReuseAttempt<>(null, "no_checkpoint");
        }
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return new ReuseAttempt<>(null, null);
        }
        String scenarioNow = request.getEvalToolScenario();
        if (scenarioNow == null || scenarioNow.isBlank()) {
            return new ReuseAttempt<>(null, null);
        }
        Map<String, Object> d = row.detail();
        if (d == null || d.isEmpty()) {
            return new ReuseAttempt<>(null, "snapshot_missing");
        }
        Object qh = d.get("query_sha256");
        if (!(qh instanceof String storedHash) || storedHash.isBlank()) {
            return new ReuseAttempt<>(null, "checkpoint_missing_query_hash");
        }
        String now = sha256Hex(request.getQuery().trim());
        if (!storedHash.trim().equalsIgnoreCase(now)) {
            return new ReuseAttempt<>(null, "query_hash_mismatch");
        }
        Object sc = d.get("eval_tool_scenario");
        if (!(sc instanceof String storedScenario) || storedScenario.isBlank()) {
            return new ReuseAttempt<>(null, "snapshot_missing");
        }
        if (!storedScenario.trim().equalsIgnoreCase(scenarioNow.trim())) {
            return new ReuseAttempt<>(null, "scenario_mismatch");
        }
        boolean used = boolFromDetail(d.get("tool_used"));
        Object nameRaw = d.get("tool_name");
        Object outcomeRaw = d.get("tool_outcome");
        if (nameRaw == null || outcomeRaw == null) {
            return new ReuseAttempt<>(null, "snapshot_missing");
        }
        String name = String.valueOf(nameRaw);
        String outcome = String.valueOf(outcomeRaw);
        long latencyMs = 0L;
        Object lm = d.get("tool_latency_ms");
        if (lm instanceof Number n) {
            latencyMs = n.longValue();
        } else if (lm instanceof String s) {
            try {
                latencyMs = Long.parseLong(s.trim());
            } catch (Exception ignored) {
                latencyMs = 0L;
            }
        }
        EvalToolStageRunner.EvalToolInvocationResult.Kind kind = kindFromOutcome(outcome);
        Boolean cb = boolFromDetail(d.get("tool_disabled_by_circuit_breaker")) ? Boolean.TRUE : null;
        Boolean rl = boolFromDetail(d.get("tool_rate_limited")) ? Boolean.TRUE : null;
        Boolean tr = boolFromDetail(d.get("tool_output_truncated")) ? Boolean.TRUE : null;
        return new ReuseAttempt<>(new EvalToolStageRunner.EvalToolInvocationResult(used, name, outcome, kind, latencyMs, cb, rl, tr), null);
    }

    private static EvalToolStageRunner.EvalToolInvocationResult.Kind kindFromOutcome(String outcome) {
        if (outcome == null) {
            return EvalToolStageRunner.EvalToolInvocationResult.Kind.OK;
        }
        String o = outcome.trim().toLowerCase(java.util.Locale.ROOT);
        return switch (o) {
            case EvalToolStageRunner.OUTCOME_TIMEOUT -> EvalToolStageRunner.EvalToolInvocationResult.Kind.TIMEOUT;
            case EvalToolStageRunner.OUTCOME_ERROR -> EvalToolStageRunner.EvalToolInvocationResult.Kind.ERROR;
            case EvalToolStageRunner.OUTCOME_DISABLED_BY_CIRCUIT_BREAKER -> EvalToolStageRunner.EvalToolInvocationResult.Kind.CIRCUIT_BREAKER;
            case EvalToolStageRunner.OUTCOME_RATE_LIMITED -> EvalToolStageRunner.EvalToolInvocationResult.Kind.RATE_LIMITED;
            default -> EvalToolStageRunner.EvalToolInvocationResult.Kind.OK;
        };
    }

    private record DedupedSlice(List<Document> docs, int totalUnique) {
    }

    /**
     * 复用 TravelAgent 的检索策略（rewrite → 多路向量召回 → 去重），并映射为对外 {@code retrieval_hits} 与 {@code sources}。
     */
    private Evidence retrieveEvidence(String query, String mode, Integer xEvalMembershipTopN) {
        com.travel.ai.agent.QueryRewriter rewriter = queryRewriter.getIfAvailable();
        VectorStore vs = vectorStore.getIfAvailable();
        if (rewriter == null || vs == null) {
            return new Evidence(List.of(), List.of(), 0, 0, false, false, false);
        }

        int topN = (xEvalMembershipTopN != null && xEvalMembershipTopN > 0) ? Math.min(50, xEvalMembershipTopN) : 8;

        // EVAL 跑批场景：避免为“证据检索”引入外部 LLM 改写（成本/延迟/不稳定）。
        // 后续若引入缓存或规则改写，可在此处平滑演进。
        boolean queryLineTruncated = false;
        List<String> queries;
        if ("EVAL".equalsIgnoreCase(mode)) {
            queries = List.of(query);
        } else {
            com.travel.ai.agent.QueryRewriter.RewriteOutcome ro = rewriter.rewriteWithOutcome(query);
            queries = ro.queries();
            queryLineTruncated = ro.anyLineTruncated();
        }

        // 检索隔离：主链路按登录 user_id；eval 跑批通常无登录态，因此使用专用 eval 租户（避免泄露真实用户数据）。
        // 注意：要让 eval 跑批命中，需要将评测用知识写入 metadata.user_id="eval"（见 KnowledgeInitializer / 知识导入逻辑）。
        String userIdForFilter = null;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && !(auth instanceof AnonymousAuthenticationToken)) {
            String currentUser = auth.getName();
            if (currentUser != null
                    && !currentUser.isBlank()
                    && !"anonymous".equalsIgnoreCase(currentUser)
                    && !"anonymoususer".equalsIgnoreCase(currentUser)) {
                userIdForFilter = currentUser;
            }
        } else {
            userIdForFilter = "eval";
        }

        Filter.Expression userFilter = (userIdForFilter == null) ? null : new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("user_id"),
                new Filter.Value(userIdForFilter)
        );

        List<Document> flat = new ArrayList<>();
        for (String q : queries) {
            flat.addAll(vs.similaritySearch(SearchRequest.builder()
                    .query(q)
                    .topK(2)
                    .filterExpression(userFilter)
                    .build()));
        }

        DedupedSlice slice = mergeAndDedupeDocuments(flat, topN);
        List<Document> docs = slice.docs();
        int candidateTotalUnique = slice.totalUnique();
        boolean candidatesCapped = candidateTotalUnique > topN;

        List<EvalChatRetrievalHit> hits = new ArrayList<>(docs.size());
        List<EvalChatSource> sources = new ArrayList<>(docs.size());
        boolean sourcesSnippetTruncated = false;
        for (Document d : docs) {
            String rawId = d.getId();
            String id = RetrievalMembershipHasher.canonicalChunkId(rawId);
            if (id.isEmpty()) {
                continue;
            }
            String title = null;
            if (d.getMetadata() != null) {
                Object src = d.getMetadata().get("source_name");
                if (src != null) {
                    title = String.valueOf(src);
                }
            }

            hits.add(new EvalChatRetrievalHit(id, title, null));

            EvalChatSource s = new EvalChatSource();
            s.setId(id);
            s.setTitle(title);
            TruncationResult tr = truncateSnippet(d.getText(), 300);
            s.setSnippet(tr.text());
            sourcesSnippetTruncated = sourcesSnippetTruncated || tr.truncated();
            s.setScore(null);
            sources.add(s);
        }

        // 先使用“sources == 前 N 条 hits”的保守策略，避免引用落在 N 之外触发 membership 假失败；
        // 后续升级（rerank/融合）只改变排序与子集选择，不改变闭环约束。
        return new Evidence(
                Collections.unmodifiableList(hits),
                Collections.unmodifiableList(sources),
                candidateTotalUnique,
                topN,
                sourcesSnippetTruncated,
                candidatesCapped,
                queryLineTruncated
        );
    }

    private record TruncationResult(String text, boolean truncated) {
    }

    private static TruncationResult truncateSnippet(String text, int maxChars) {
        if (text == null) {
            return new TruncationResult("", false);
        }
        String t = text.trim();
        if (t.length() <= maxChars) {
            return new TruncationResult(t, false);
        }
        return new TruncationResult(t.substring(0, maxChars) + "…", true);
    }

    private static DedupedSlice mergeAndDedupeDocuments(List<Document> documents, int maxDocs) {
        var seen = new LinkedHashMap<String, Document>();
        if (documents == null) {
            return new DedupedSlice(List.of(), 0);
        }
        for (Document d : documents) {
            if (d == null) {
                continue;
            }
            String key;
            if (d.getId() != null && !d.getId().isBlank()) {
                key = "id:" + RetrievalMembershipHasher.canonicalChunkId(d.getId());
            } else {
                String text = d.getText() != null ? d.getText() : "";
                key = "text:" + text.hashCode();
            }
            seen.putIfAbsent(key, d);
        }
        int totalUnique = seen.size();
        var all = new ArrayList<>(seen.values());
        int take = Math.min(maxDocs, totalUnique);
        List<Document> slice = new ArrayList<>(all.subList(0, take));
        return new DedupedSlice(Collections.unmodifiableList(slice), totalUnique);
    }
}
