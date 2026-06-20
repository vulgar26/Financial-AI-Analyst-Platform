package com.travel.ai.agent.state;

import com.travel.ai.agent.FinancialAnalystAgentImpl;
import com.travel.ai.agent.guard.RetrieveEmptyHitGate;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.StageEvent;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.trace.StageTrace;
import com.travel.ai.runtime.trace.ToolTrace;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Mutable state for one mainline agent turn.
 *
 * <p>This class is intentionally field-based in W1 to preserve the previous
 * {@code FinancialAnalystAgentImpl.MainAgentTurnContext} access pattern and avoid behavioral
 * changes while the monolithic agent adapter is being split.</p>
 */
public final class WorkflowTurnState {

    public final String conversationId;
    public final String userMessage;
    public final String requestId;

    /** 主线 SSE 可观测：阶段事件（A 粒度）。在 FinancialAnalystAgentImpl stage execution 期间顺序追加，chat() 再一次性拼进 Flux。 */
    public final List<StageEvent> stageEvents = new ArrayList<>();
    /** 阶段耗时（毫秒），由 FinancialAnalystAgentImpl stage boundary logging 写入。 */
    public final Map<StageName, Long> stageElapsedMs = new LinkedHashMap<>();
    /** 主线 SSE 可观测：策略/决策事件（与 eval meta.policy_events 同语义）。 */
    public final List<PolicyEvent> policyEvents = new ArrayList<>();
    /** Runtime R3 internal traces. They are not emitted to SSE or eval in R3B. */
    public final List<StageTrace> runtimeStageTraces = new ArrayList<>();
    public final List<ToolTrace> runtimeToolTraces = new ArrayList<>();
    public boolean workflowRuntimePath;
    /**
     * WRITE 子流：LLM {@code .timeout(llm_stream)} 或其它异常经 onErrorResume 降级为占位文本时，
     * 写入对应 {@code error_code}，供 {@link FinancialAnalystAgentImpl#chat} 注入 {@code event:error}。
     */
    public final AtomicReference<String> llmStreamErrorCode = new AtomicReference<>();

    public String currentUser;
    public Filter.Expression userFilter;
    public List<String> queries;
    public long rewriteMs;
    public List<Document> docs;
    public long retrieveMs;
    /** 不含工具数据块的用户 prompt 片段（检索上下文 + 用户问题）。 */
    public String promptBase;
    public String toolPreface;
    /** 送入 LLM 的最终 prompt（工具块 + promptBase）。 */
    public String finalPromptForLlm;
    /** SSE 首包「引用片段」正文。 */
    public String citationBlock;

    /** PLAN 阶段产出的 JSON 文本（含 {@code steps} 数组），并入 WRITE 前最终 prompt。 */
    public String planJson;

    /** 与评测 {@code meta} 及 SSE {@code event:plan_parse} 对齐的附录 E 解析结论（由 {@link PlanService} 写回）。 */
    public String planDraftSource;
    public String planParseOutcome;
    public int planParseAttempts;
    public String planParseResolved;

    /**
     * 本轮检索 replan 次数（图编排回边）。GUARD 检测到「假零命中」(纯零命中、无工具数据)
     * 时跳回 RETRIEVE 用更低相似度阈值再探一次。业务语义上限为 1：探一次足以判别
     * 「真没有」还是「被阈值拦掉」，多探属于「为了查到而查」，是制造另一种说谎的绿。
     * 引擎侧另有 maxRedirects 作为机械安全兜底，两层职责不同。
     */
    public int retrievalReplanCount;

    /**
     * 第二刀：本轮 GUARD 的语义相关性裁判判定「检索到了但不相关」，已请求回边「换个问法重检」。
     * stageRetrieve 读它给 query 改写加提示前缀；与 {@link #retrievalReplanCount} 共用上界
     * （无论「没捞到」还是「捞错了」，都只给一次重探机会）。重检后清零，避免影响后续判断。
     */
    public boolean relevanceReplanRequested;

    /** 检索零命中且策略为 clarify 时跳过 LLM，仅下发固定澄清。 */
    public boolean skipLlmForEmptyHits;
    public String emptyHitsClarifyBody;
    /** 与 {@link RetrieveEmptyHitGate.Decision#skipGateErrorCode()} 对齐，仅 skip LLM 时有值。 */
    public String emptyHitsGateLogCode;
    /**
     * 门控澄清路径下 Redis memory 写入必须保证只追加一轮；merge/多订阅可能触发多次 complete。
     */
    public final AtomicBoolean emptyHitsMemoryWritten = new AtomicBoolean(false);

    public WorkflowTurnState(String conversationId, String userMessage, String requestId) {
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
