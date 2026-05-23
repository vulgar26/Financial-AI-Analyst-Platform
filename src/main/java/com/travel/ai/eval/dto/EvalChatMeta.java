package com.travel.ai.eval.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;
import java.util.Map;

/**
 * 评测响应 {@code meta}：P0 至少包含 {@code mode}；另含 travel-ai 可控性字段（见 {@code plans/p0-execution-map.md} 附录 C3）。
 * <p>
 * 联调说明：上游 eval 平台会将本对象整段快照落库为 {@code eval_result.target_meta_json}（受体积与安全策略约束），
 * compare 摘要中的 {@code *_meta_trace} 仅包含各 target 在运维侧配置的 {@code eval.targets[].meta-trace-keys} 所列键；
 * 未配置的键不会出现在 compare trace 中（全量仍以导出接口中的 {@code meta} 为准）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EvalChatMeta {

    /**
     * P0 硬指标：禁止动态 replan 循环，对外 {@code replan_count} 必须恒为该值（eval 可回归统计）。
     */
    public static final int P0_REPLAN_COUNT = 0;

    /**
     * 本轮模式：如 {@code EVAL}、{@code AGENT}；若请求体未传 {@code mode}，默认 {@code EVAL}。
     */
    private String mode;

    /** 单次请求唯一 id（UUID），便于与网关/日志 trace 对齐。 */
    private String requestId;

    private String contractVersion;

    private String workflowId;

    private String workflowVersion;

    private String workflowFamily;

    /**
     * 本请求实际经过的线性阶段序列（大写），为 {@code PLAN|RETRIEVE|TOOL|GUARD|WRITE} 的子序列（与主线 SSE 一致；
     * 长度随附录 E {@code plan.steps} 物理跳过而变短）。
     * 空 query 等未跑流水线时为 {@code []}。
     */
    private List<String> stageOrder;

    /** 实际执行过的阶段数；应与 {@code stage_order.length} 一致（串行计数）。 */
    private int stepCount;

    /** P0 禁止 replan 循环，恒为 {@code 0}。 */
    private int replanCount;

    /**
     * 与主线共用的 {@code app.agent.total-timeout}（毫秒），便于 eval 对账与回归。
     */
    private Long agentTotalTimeoutMs;

    /** {@code app.agent.max-steps} 配置值。 */
    private Integer agentMaxStepsConfigured;

    /** {@code app.agent.tool-timeout}（毫秒）。 */
    private Long agentToolTimeoutMs;

    /** {@code app.agent.llm-stream-timeout}（毫秒）。 */
    private Long agentLlmStreamTimeoutMs;

    /**
     * 执行配置快照（hash）：用于 compare / 回放时确认“同一题库下是否确实仅改了某个开关”。\n
     * <p>
     * 约束：仅 hash，不回显配置原文（避免泄露密钥/环境细节与体积膨胀）。
     */
    private String configSnapshotHash;

    /** 与 {@link #configSnapshotHash} 同时出现；固定为 {@code SHA-256}。 */
    private String configSnapshotHashAlg;

    /** 与 {@link #configSnapshotHash} 同时出现；描述 hash 覆盖的配置键范围（人读）。 */
    private String configSnapshotHashScope;

    /**
     * 与本机配置白名单快照绑定的稳定引用 id（与 {@link #configSnapshotHash} 一一对应）。\n
     * 形如 {@code travel-ai:config-snapshot/v1/sha256/<64位小写hex>}，便于报表 join 或后续外存对象主键；无 hash 时不出现。
     */
    private String configSnapshotId;

    /**
     * 可选：与 {@link #configSnapshotHash} 同源的白名单配置键值（值均为字符串），便于人机可读对账。\n
     * 受 {@code app.eval.config-snapshot-meta-enabled} 控制；默认不出现。
     */
    private Map<String, String> configSnapshot;

    /**
     * 近似的上下文规模统计（字符数）：用于回归时观察“上下文突然变短/变长”。
     * <p>
     * 说明：这是<strong>字符级</strong>近似统计，不等价于供应商返回的 token 计数；用于趋势与异常检测。
     */
    private Integer contextCharCount;

    /** 与 {@link #contextCharCount} 配套：按经验系数粗略估算的 token 数（默认以 4 chars ≈ 1 token）。 */
    private Integer contextTokenEstimate;

    /** 组成项：本轮 query 字符数。 */
    private Integer contextQueryCharCount;

    /** 组成项：本轮 sources snippet 总字符数（已截断后的文本）。 */
    private Integer contextSourcesSnippetCharCount;

    /** 预算口径：评测口 {@code sources[].snippet} 规则截断上限（字符）。 */
    private Integer contextBudgetSourcesSnippetMaxChars;

    /** 预算口径：token 近似估算的 chars/token 系数（例如 4 chars ≈ 1 token）。 */
    private Integer contextBudgetCharsPerTokenEstimate;

    /**
     * token 统计来源：{@code provider|estimate|tokenizer}。\n
     * <p>
     * P1-0 组合策略：默认 {@code estimate}（离线稳定）；在显式启用 real LLM 且可提取 usage 时为 {@code provider}。
     */
    private String tokenSource;

    /** 真 token（若可得）：prompt/input tokens。 */
    private Integer promptTokens;
    /** 真 token（若可得）：completion/output tokens。 */
    private Integer completionTokens;
    /** 真 token（若可得）：total tokens（可由前两者推导）。 */
    private Integer totalTokens;

    /**
     * provider usage 是否成功获取（仅在请求 {@code llm_mode=real} 且服务端允许时才可能出现）。\n
     * 用于区分“走了 real 但 SDK 未暴露 usage / 超时 / 失败”等情况。
     */
    private Boolean providerUsageAvailable;

    /**
     * provider usage 获取失败的原因标签（不含敏感信息），如 {@code timeout|no_usage|error|no_client}；\n
     * 标签门禁未通过时为 {@code tag_gate_no_tags}（未传 {@code eval_tags}）或 {@code tag_gate_no_match}（无命中配置前缀）。
     */
    private String providerUsageFailureReason;

    /**
     * 当 {@code latency_ms}（Controller 写入）大于 {@link #agentTotalTimeoutMs} 时为 {@code true}；
     * 未写入总超时或未比较时为 {@code null}。
     */
    private Boolean agentLatencyBudgetExceeded;

    /**
     * Plan 解析尝试次数：{@code 1} 首次成功；{@code 2} 首次失败后经过一次 repair 再解析。
     * 未执行 plan 解析（如空 query）时不返回该字段。
     */
    private Integer planParseAttempts;

    /**
     * {@code success|repaired|failed}，见 {@code plans/travel-ai-upgrade.md} P0 Plan 解析治理。
     */
    private String planParseOutcome;

    /**
     * 本请求工具调用次数（P0 串行，Day6 stub 为 0 或 1）；未走评测工具场景时不返回。
     */
    private Integer toolCallsCount;

    /**
     * {@code ok|timeout|error|disabled_by_circuit_breaker|rate_limited|…}，与顶层 {@code tool.outcome} 一致；
     * 未走工具场景时不返回。
     */
    private String toolOutcome;

    /** 工具调用耗时（ms）；未走工具场景时不返回。 */
    private Long toolLatencyMs;

    /** 工具是否因熔断被禁用；未走工具场景或未启用熔断时不返回。 */
    private Boolean toolDisabledByCircuitBreaker;

    /** 工具是否因限流被拒绝；未走工具场景或未启用限流时不返回。 */
    private Boolean toolRateLimited;

    /** 工具输出是否被截断/摘要；未走工具场景时不返回。 */
    private Boolean toolOutputTruncated;

    /**
     * 本轮执行中是否发生过“上下文/证据截断”（如 sources snippet 规则截断、工具输出截断等）。
     * <p>
     * 目的：在 compare / run.report 中快速区分「质量变化来自策略」还是「来自预算截断」。
     */
    private Boolean contextTruncated;

    /**
     * 截断原因枚举（无需包含敏感原文），例如：{@code sources_snippet_truncated}、{@code tool_output_truncated}、
     * {@code retrieval_candidates_capped}（去重后命中数超过对外返回上限）、{@code retrieval_query_line_truncated}（非 {@code EVAL} 模式下改写单行超长截断）。
     */
    private List<String> contextTruncationReasons;

    /**
     * 是否处于低置信/空命中等门控（P0 不启用 score 阈值时仍可由 stub 置 true 供 eval 统计）。
     */
    private Boolean lowConfidence;

    /**
     * 低置信 / 门控原因列表（与 Vagent 常见字段 {@code low_confidence_reasons} 对齐，JSON snake_case）。
     */
    private List<String> lowConfidenceReasons;

    /**
     * 仅在 SafetyGate 规则短路时写入，用于报表分桶与归因。
     * <p>
     * 约束：非短路路径必须不返回该字段（避免污染统计口径）。
     */
    private String evalSafetyRuleId;

    /**
     * 结构化策略/门控决策轨迹（无 query 原文）；仅当至少发生一条可归因决策时出现。
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EvalChatPolicyEvent> policyEvents;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EvalChatStageTrace> stageTrace;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<EvalChatToolTrace> toolTrace;

    private EvalChatEvidenceSummary evidenceSummary;

    /**
     * 检索命中条数（评测 stub）；空命中场景为 0；未参与门控时不返回。
     */
    private Integer retrieveHitCount;

    /**
     * eval-upgrade.md E7：候选 hitId 的 HMAC-SHA256 证据（64 位小写 hex）；须非空数组才视为携带证据。
     */
    private List<String> retrievalHitIdHashes;

    /** 与 {@link #retrievalHitIdHashes} 同时出现；固定为 {@code HMAC-SHA256}。 */
    private String retrievalHitIdHashAlg;

    /** 与 {@link #retrievalHitIdHashes} 同时出现；固定为 {@code x-eval-token/v1}。 */
    private String retrievalHitIdHashKeyDerivation;

    /** 与 {@link #retrievalHitIdHashes} 同时出现：候选截断上限 N。 */
    private Integer retrievalCandidateLimitN;

    /** 与 {@link #retrievalHitIdHashes} 同时出现：去重后的候选总数（截断前）。 */
    private Integer retrievalCandidateTotal;

    /** 与 {@link #retrievalHitIdHashes} 同时出现：本 target 使用的 canonical id 口径。 */
    private String canonicalHitIdScheme;

    /**
     * 一次性 recovery 占位结论（与 {@code replan_count=0} 正交）：如 {@code none|aborted|continue|suggest_clarify}；
     * 受 {@code app.eval.reflection-meta-enabled} 控制，关闭时不序列化。
     */
    private String recoveryAction;

    /**
     * 结构化自检摘要（评测 stub）；与 {@link #recoveryAction} 配套出现；无场景时可为空。
     */
    private Map<String, Object> selfCheck;

    /**
     * 断点续跑结果：{@code plan_mismatch|resume_exhausted|resumed|matched_non_resumable}；无断点逻辑时不出现。
     */
    private String evalCheckpointOutcome;

    /**
     * 断点复用命中：证据（retrieval_hits/sources）是否直接从 checkpoint 复用；仅命中时返回 true。
     */
    private Boolean checkpointEvidenceReused;

    /**
     * 断点复用命中：TOOL 结果是否直接从 checkpoint 复用；仅命中时返回 true。
     */
    private Boolean checkpointToolReused;

    /**
     * 断点复用未命中原因（证据）：仅当<strong>尝试</strong>从 checkpoint 复用证据但失败时返回，例如：
     * {@code no_checkpoint|checkpoint_missing_query_hash|query_hash_mismatch|snapshot_missing}。
     */
    private String checkpointEvidenceReuseMissReason;

    /**
     * 断点复用未命中原因（工具）：仅当<strong>尝试</strong>从 checkpoint 复用 TOOL 结果但失败时返回，例如：
     * {@code no_checkpoint|checkpoint_missing_query_hash|query_hash_mismatch|scenario_mismatch|snapshot_missing}。
     */
    private String checkpointToolReuseMissReason;

    public EvalChatMeta() {
    }

    public EvalChatMeta(String mode, String requestId) {
        this.mode = mode;
        this.requestId = requestId;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getContractVersion() {
        return contractVersion;
    }

    public void setContractVersion(String contractVersion) {
        this.contractVersion = contractVersion;
    }

    public String getWorkflowId() {
        return workflowId;
    }

    public void setWorkflowId(String workflowId) {
        this.workflowId = workflowId;
    }

    public String getWorkflowVersion() {
        return workflowVersion;
    }

    public void setWorkflowVersion(String workflowVersion) {
        this.workflowVersion = workflowVersion;
    }

    public String getWorkflowFamily() {
        return workflowFamily;
    }

    public void setWorkflowFamily(String workflowFamily) {
        this.workflowFamily = workflowFamily;
    }

    public List<String> getStageOrder() {
        return stageOrder;
    }

    public void setStageOrder(List<String> stageOrder) {
        this.stageOrder = stageOrder;
    }

    public int getStepCount() {
        return stepCount;
    }

    public void setStepCount(int stepCount) {
        this.stepCount = stepCount;
    }

    public int getReplanCount() {
        return replanCount;
    }

    public void setReplanCount(int replanCount) {
        this.replanCount = replanCount;
    }

    public Long getAgentTotalTimeoutMs() {
        return agentTotalTimeoutMs;
    }

    public void setAgentTotalTimeoutMs(Long agentTotalTimeoutMs) {
        this.agentTotalTimeoutMs = agentTotalTimeoutMs;
    }

    public Integer getAgentMaxStepsConfigured() {
        return agentMaxStepsConfigured;
    }

    public void setAgentMaxStepsConfigured(Integer agentMaxStepsConfigured) {
        this.agentMaxStepsConfigured = agentMaxStepsConfigured;
    }

    public Long getAgentToolTimeoutMs() {
        return agentToolTimeoutMs;
    }

    public void setAgentToolTimeoutMs(Long agentToolTimeoutMs) {
        this.agentToolTimeoutMs = agentToolTimeoutMs;
    }

    public Long getAgentLlmStreamTimeoutMs() {
        return agentLlmStreamTimeoutMs;
    }

    public void setAgentLlmStreamTimeoutMs(Long agentLlmStreamTimeoutMs) {
        this.agentLlmStreamTimeoutMs = agentLlmStreamTimeoutMs;
    }

    public String getConfigSnapshotHash() {
        return configSnapshotHash;
    }

    public void setConfigSnapshotHash(String configSnapshotHash) {
        this.configSnapshotHash = configSnapshotHash;
    }

    public String getConfigSnapshotHashAlg() {
        return configSnapshotHashAlg;
    }

    public void setConfigSnapshotHashAlg(String configSnapshotHashAlg) {
        this.configSnapshotHashAlg = configSnapshotHashAlg;
    }

    public String getConfigSnapshotHashScope() {
        return configSnapshotHashScope;
    }

    public void setConfigSnapshotHashScope(String configSnapshotHashScope) {
        this.configSnapshotHashScope = configSnapshotHashScope;
    }

    public String getConfigSnapshotId() {
        return configSnapshotId;
    }

    public void setConfigSnapshotId(String configSnapshotId) {
        this.configSnapshotId = configSnapshotId;
    }

    public Map<String, String> getConfigSnapshot() {
        return configSnapshot;
    }

    public void setConfigSnapshot(Map<String, String> configSnapshot) {
        this.configSnapshot = configSnapshot;
    }

    public Integer getContextCharCount() {
        return contextCharCount;
    }

    public void setContextCharCount(Integer contextCharCount) {
        this.contextCharCount = contextCharCount;
    }

    public Integer getContextTokenEstimate() {
        return contextTokenEstimate;
    }

    public void setContextTokenEstimate(Integer contextTokenEstimate) {
        this.contextTokenEstimate = contextTokenEstimate;
    }

    public Integer getContextQueryCharCount() {
        return contextQueryCharCount;
    }

    public void setContextQueryCharCount(Integer contextQueryCharCount) {
        this.contextQueryCharCount = contextQueryCharCount;
    }

    public Integer getContextSourcesSnippetCharCount() {
        return contextSourcesSnippetCharCount;
    }

    public void setContextSourcesSnippetCharCount(Integer contextSourcesSnippetCharCount) {
        this.contextSourcesSnippetCharCount = contextSourcesSnippetCharCount;
    }

    public Integer getContextBudgetSourcesSnippetMaxChars() {
        return contextBudgetSourcesSnippetMaxChars;
    }

    public void setContextBudgetSourcesSnippetMaxChars(Integer contextBudgetSourcesSnippetMaxChars) {
        this.contextBudgetSourcesSnippetMaxChars = contextBudgetSourcesSnippetMaxChars;
    }

    public Integer getContextBudgetCharsPerTokenEstimate() {
        return contextBudgetCharsPerTokenEstimate;
    }

    public void setContextBudgetCharsPerTokenEstimate(Integer contextBudgetCharsPerTokenEstimate) {
        this.contextBudgetCharsPerTokenEstimate = contextBudgetCharsPerTokenEstimate;
    }

    public String getTokenSource() {
        return tokenSource;
    }

    public void setTokenSource(String tokenSource) {
        this.tokenSource = tokenSource;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Boolean getProviderUsageAvailable() {
        return providerUsageAvailable;
    }

    public void setProviderUsageAvailable(Boolean providerUsageAvailable) {
        this.providerUsageAvailable = providerUsageAvailable;
    }

    public String getProviderUsageFailureReason() {
        return providerUsageFailureReason;
    }

    public void setProviderUsageFailureReason(String providerUsageFailureReason) {
        this.providerUsageFailureReason = providerUsageFailureReason;
    }

    public Boolean getAgentLatencyBudgetExceeded() {
        return agentLatencyBudgetExceeded;
    }

    public void setAgentLatencyBudgetExceeded(Boolean agentLatencyBudgetExceeded) {
        this.agentLatencyBudgetExceeded = agentLatencyBudgetExceeded;
    }

    public Integer getPlanParseAttempts() {
        return planParseAttempts;
    }

    public void setPlanParseAttempts(Integer planParseAttempts) {
        this.planParseAttempts = planParseAttempts;
    }

    public String getPlanParseOutcome() {
        return planParseOutcome;
    }

    public void setPlanParseOutcome(String planParseOutcome) {
        this.planParseOutcome = planParseOutcome;
    }

    public Integer getToolCallsCount() {
        return toolCallsCount;
    }

    public void setToolCallsCount(Integer toolCallsCount) {
        this.toolCallsCount = toolCallsCount;
    }

    public String getToolOutcome() {
        return toolOutcome;
    }

    public void setToolOutcome(String toolOutcome) {
        this.toolOutcome = toolOutcome;
    }

    public Long getToolLatencyMs() {
        return toolLatencyMs;
    }

    public void setToolLatencyMs(Long toolLatencyMs) {
        this.toolLatencyMs = toolLatencyMs;
    }

    public Boolean getToolDisabledByCircuitBreaker() {
        return toolDisabledByCircuitBreaker;
    }

    public void setToolDisabledByCircuitBreaker(Boolean toolDisabledByCircuitBreaker) {
        this.toolDisabledByCircuitBreaker = toolDisabledByCircuitBreaker;
    }

    public Boolean getToolRateLimited() {
        return toolRateLimited;
    }

    public void setToolRateLimited(Boolean toolRateLimited) {
        this.toolRateLimited = toolRateLimited;
    }

    public Boolean getToolOutputTruncated() {
        return toolOutputTruncated;
    }

    public void setToolOutputTruncated(Boolean toolOutputTruncated) {
        this.toolOutputTruncated = toolOutputTruncated;
    }

    public Boolean getContextTruncated() {
        return contextTruncated;
    }

    public void setContextTruncated(Boolean contextTruncated) {
        this.contextTruncated = contextTruncated;
    }

    public List<String> getContextTruncationReasons() {
        return contextTruncationReasons;
    }

    public void setContextTruncationReasons(List<String> contextTruncationReasons) {
        this.contextTruncationReasons = contextTruncationReasons;
    }

    public Boolean getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(Boolean lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public List<String> getLowConfidenceReasons() {
        return lowConfidenceReasons;
    }

    public void setLowConfidenceReasons(List<String> lowConfidenceReasons) {
        this.lowConfidenceReasons = lowConfidenceReasons;
    }

    public String getEvalSafetyRuleId() {
        return evalSafetyRuleId;
    }

    public void setEvalSafetyRuleId(String evalSafetyRuleId) {
        this.evalSafetyRuleId = evalSafetyRuleId;
    }

    public List<EvalChatPolicyEvent> getPolicyEvents() {
        return policyEvents;
    }

    public void setPolicyEvents(List<EvalChatPolicyEvent> policyEvents) {
        this.policyEvents = policyEvents;
    }

    public List<EvalChatStageTrace> getStageTrace() {
        return stageTrace;
    }

    public void setStageTrace(List<EvalChatStageTrace> stageTrace) {
        this.stageTrace = stageTrace;
    }

    public List<EvalChatToolTrace> getToolTrace() {
        return toolTrace;
    }

    public void setToolTrace(List<EvalChatToolTrace> toolTrace) {
        this.toolTrace = toolTrace;
    }

    public EvalChatEvidenceSummary getEvidenceSummary() {
        return evidenceSummary;
    }

    public void setEvidenceSummary(EvalChatEvidenceSummary evidenceSummary) {
        this.evidenceSummary = evidenceSummary;
    }

    public Integer getRetrieveHitCount() {
        return retrieveHitCount;
    }

    public void setRetrieveHitCount(Integer retrieveHitCount) {
        this.retrieveHitCount = retrieveHitCount;
    }

    public List<String> getRetrievalHitIdHashes() {
        return retrievalHitIdHashes;
    }

    public void setRetrievalHitIdHashes(List<String> retrievalHitIdHashes) {
        this.retrievalHitIdHashes = retrievalHitIdHashes;
    }

    public String getRetrievalHitIdHashAlg() {
        return retrievalHitIdHashAlg;
    }

    public void setRetrievalHitIdHashAlg(String retrievalHitIdHashAlg) {
        this.retrievalHitIdHashAlg = retrievalHitIdHashAlg;
    }

    public String getRetrievalHitIdHashKeyDerivation() {
        return retrievalHitIdHashKeyDerivation;
    }

    public void setRetrievalHitIdHashKeyDerivation(String retrievalHitIdHashKeyDerivation) {
        this.retrievalHitIdHashKeyDerivation = retrievalHitIdHashKeyDerivation;
    }

    public Integer getRetrievalCandidateLimitN() {
        return retrievalCandidateLimitN;
    }

    public void setRetrievalCandidateLimitN(Integer retrievalCandidateLimitN) {
        this.retrievalCandidateLimitN = retrievalCandidateLimitN;
    }

    public Integer getRetrievalCandidateTotal() {
        return retrievalCandidateTotal;
    }

    public void setRetrievalCandidateTotal(Integer retrievalCandidateTotal) {
        this.retrievalCandidateTotal = retrievalCandidateTotal;
    }

    public String getCanonicalHitIdScheme() {
        return canonicalHitIdScheme;
    }

    public void setCanonicalHitIdScheme(String canonicalHitIdScheme) {
        this.canonicalHitIdScheme = canonicalHitIdScheme;
    }

    public String getRecoveryAction() {
        return recoveryAction;
    }

    public void setRecoveryAction(String recoveryAction) {
        this.recoveryAction = recoveryAction;
    }

    public Map<String, Object> getSelfCheck() {
        return selfCheck;
    }

    public void setSelfCheck(Map<String, Object> selfCheck) {
        this.selfCheck = selfCheck;
    }

    public String getEvalCheckpointOutcome() {
        return evalCheckpointOutcome;
    }

    public void setEvalCheckpointOutcome(String evalCheckpointOutcome) {
        this.evalCheckpointOutcome = evalCheckpointOutcome;
    }

    public Boolean getCheckpointEvidenceReused() {
        return checkpointEvidenceReused;
    }

    public void setCheckpointEvidenceReused(Boolean checkpointEvidenceReused) {
        this.checkpointEvidenceReused = checkpointEvidenceReused;
    }

    public Boolean getCheckpointToolReused() {
        return checkpointToolReused;
    }

    public void setCheckpointToolReused(Boolean checkpointToolReused) {
        this.checkpointToolReused = checkpointToolReused;
    }

    public String getCheckpointEvidenceReuseMissReason() {
        return checkpointEvidenceReuseMissReason;
    }

    public void setCheckpointEvidenceReuseMissReason(String checkpointEvidenceReuseMissReason) {
        this.checkpointEvidenceReuseMissReason = checkpointEvidenceReuseMissReason;
    }

    public String getCheckpointToolReuseMissReason() {
        return checkpointToolReuseMissReason;
    }

    public void setCheckpointToolReuseMissReason(String checkpointToolReuseMissReason) {
        this.checkpointToolReuseMissReason = checkpointToolReuseMissReason;
    }
}
