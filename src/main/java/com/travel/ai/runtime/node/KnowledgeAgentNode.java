package com.travel.ai.runtime.node;

import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.retrieve.EvidencePackage;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Knowledge Agent：检索闭环自治的复合节点。
 *
 * <p>子图 {@code [RETRIEVE → JUDGE]}，JUDGE 判定需要重检时发回边跳回 RETRIEVE，由子 runtime 的
 * {@code maxRedirects} 兜底终止。它<strong>自己决定 replan</strong>（零命中 / 检索到但不相关），
 * 外层只拿到最终 {@link EvidencePackage}，看不见它内部重检了几趟——这就是「自治」。</p>
 *
 * <p><strong>与旧 GUARD-replan 的关键差异（甲方案、刻意解耦）</strong>：旧逻辑的零命中重查会偷看
 * 工具结果（「工具有数据就不重查」）。本 Agent 在检索阶段根本没有工具牌，故零命中重查纯看检索
 * 质量。代价：某场景多一次检索；收益：重查（检索质量）与澄清（回答策略）职责彻底分离。</p>
 *
 * <p>本类只是「装配厂」：把两个子节点 + 子 runtime + lift 装成一个 {@link AgentNode}。</p>
 */
public final class KnowledgeAgentNode {

    /** 外层图里 Knowledge Agent 的节点名。 */
    public static final String NAME = "KNOWLEDGE";
    /** 子图产物槽 / 外层产物槽里 EvidencePackage 的 key。 */
    public static final String PRODUCT_EVIDENCE = "evidence";

    /** 业务语义上限：一次重检足以判别真缺失/假零命中，与旧 MAX_RETRIEVAL_REPLANS 对齐。 */
    public static final int DEFAULT_MAX_REPLANS = 1;

    private static final String ATTR_REPLAN_COUNT = "replan_count";
    private static final String ATTR_RELEVANCE_REPLAN = "relevance_replan";
    private static final String JUDGE = "JUDGE";

    private KnowledgeAgentNode() {
    }

    /** 用默认子 runtime（{@link LinearWorkflowRuntime#DEFAULT_MAX_REDIRECTS} 兜底）装配。 */
    public static AgentNode of(KnowledgeAgentDelegate delegate) {
        return of(delegate, new LinearWorkflowRuntime(), DEFAULT_MAX_REPLANS);
    }

    public static AgentNode of(KnowledgeAgentDelegate delegate, LinearWorkflowRuntime subRuntime, int maxReplans) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        int cap = Math.max(0, maxReplans);
        List<WorkflowNode> subGraph = List.of(
                new RetrieveSubNode(delegate),
                new JudgeSubNode(delegate, cap)
        );
        return new AgentNode(NAME, subGraph, subRuntime, KnowledgeAgentNode::lift);
    }

    /** 桥：只把证据袋从子 context 提升到外层 context，子图内部状态不外泄。 */
    private static void lift(WorkflowContext subCtx, WorkflowContext outerCtx) {
        EvidencePackage evidence = subCtx.getProduct(PRODUCT_EVIDENCE, EvidencePackage.class);
        if (evidence != null) {
            outerCtx.putProduct(PRODUCT_EVIDENCE, evidence);
        }
    }

    private static int replanCount(WorkflowContext ctx) {
        try {
            return Integer.parseInt(ctx.getAttrs().getOrDefault(ATTR_REPLAN_COUNT, "0"));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 子图节点 1：检索。读子 context 的 replan 状态决定阈值/改写提示，产出证据袋落子 context 产物槽。 */
    static final class RetrieveSubNode implements WorkflowNode {

        private final KnowledgeAgentDelegate delegate;

        RetrieveSubNode(KnowledgeAgentDelegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return StageName.RETRIEVE.name();
        }

        @Override
        public NodeResult execute(WorkflowContext ctx) {
            int attempt = replanCount(ctx);
            boolean relevanceReplan = Boolean.parseBoolean(
                    ctx.getAttrs().getOrDefault(ATTR_RELEVANCE_REPLAN, "false"));
            EvidencePackage evidence = delegate.retrieve(attempt, relevanceReplan);
            ctx.putProduct(PRODUCT_EVIDENCE, evidence);
            // 相关性重检提示一次性消费，避免污染下一趟判断。
            if (relevanceReplan) {
                ctx.putAttr(ATTR_RELEVANCE_REPLAN, "false");
            }
            return NodeResult.success();
        }
    }

    /** 子图节点 2：裁判 + 回边决策。两刀共用 replan 计数与上界。 */
    static final class JudgeSubNode implements WorkflowNode {

        private final KnowledgeAgentDelegate delegate;
        private final int maxReplans;

        JudgeSubNode(KnowledgeAgentDelegate delegate, int maxReplans) {
            this.delegate = delegate;
            this.maxReplans = maxReplans;
        }

        @Override
        public String name() {
            return JUDGE;
        }

        @Override
        public NodeResult execute(WorkflowContext ctx) {
            EvidencePackage evidence = ctx.getProduct(PRODUCT_EVIDENCE, EvidencePackage.class);
            List<Document> docs = evidence != null ? evidence.docs() : List.of();
            int attempt = replanCount(ctx);
            boolean canReplan = attempt < maxReplans;

            // 第一刀：零命中重查（纯看检索质量，不偷看工具——甲方案解耦）。
            if (docs.isEmpty()) {
                if (canReplan) {
                    return requestReplan(ctx, attempt, "RAG_EMPTY", false);
                }
                return NodeResult.success(Map.of("judge_outcome", "empty_capped"));
            }

            // 第二刀：检索非空但可能捞错了——LLM 裁判判相关性，不相关则换问法重检。
            if (canReplan) {
                RetrievalRelevanceJudge.Verdict verdict = delegate.judge(docs);
                // fail-safe：裁判不可用绝不触发 replan，避免外部抖动变功能 bug。
                if (verdict != null && verdict.available() && !verdict.relevant()) {
                    return requestReplan(ctx, attempt, "NOT_RELEVANT", true);
                }
            }
            return NodeResult.success(Map.of("judge_outcome", "relevant"));
        }

        private NodeResult requestReplan(WorkflowContext ctx, int attempt, String reason, boolean relevance) {
            ctx.putAttr(ATTR_REPLAN_COUNT, String.valueOf(attempt + 1));
            if (relevance) {
                ctx.putAttr(ATTR_RELEVANCE_REPLAN, "true");
            }
            return NodeResult.redirectTo(StageName.RETRIEVE.name(),
                    Map.of("replan", "true", "replan_reason", reason));
        }
    }
}
