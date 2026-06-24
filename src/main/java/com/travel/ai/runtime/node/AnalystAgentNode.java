package com.travel.ai.runtime.node;

import com.travel.ai.agent.prompt.AnalysisPackage;
import com.travel.ai.agent.retrieve.EvidencePackage;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Analyst Agent：工具 + 护栏 + prompt 拼装的复合节点。
 *
 * <p>子图 {@code [TOOL → GUARD]}，<strong>无回边</strong>——重查（回边）整个归 Knowledge Agent，
 * 这里只做「检索袋 + 工具 → 要不要短路澄清」的纯规则判定与 prompt 拼装。这正是甲方案解耦的
 * 分析员侧体现：重查归检索质量（Knowledge），澄清归回答策略（Analyst）。</p>
 *
 * <p><strong>只读资料袋（隔离）</strong>：Analyst 通过 {@code evidenceSource} 读 Knowledge 产出的
 * {@link EvidencePackage}，拿不到 Knowledge 子图内部状态（replan 计数等）——物理够不着，不是君子协定。
 * 产出 {@link AnalysisPackage} 落外层产物槽。</p>
 */
public final class AnalystAgentNode {

    /** 外层图里 Analyst Agent 的节点名。 */
    public static final String NAME = "ANALYST";
    /** 外层产物槽里 AnalysisPackage 的 key。 */
    public static final String PRODUCT_ANALYSIS = "analysis";

    private AnalystAgentNode() {
    }

    public static AgentNode of(AnalystAgentDelegate delegate, Supplier<EvidencePackage> evidenceSource) {
        return of(delegate, evidenceSource, new LinearWorkflowRuntime());
    }

    /**
     * @param delegate       工具/护栏/prompt 的 I/O 边界
     * @param evidenceSource 从外层读 Knowledge 产出的证据袋（隔离的唯一入口）
     * @param subRuntime     子图引擎
     */
    public static AgentNode of(AnalystAgentDelegate delegate,
                               Supplier<EvidencePackage> evidenceSource,
                               LinearWorkflowRuntime subRuntime) {
        Objects.requireNonNull(delegate, "delegate must not be null");
        Objects.requireNonNull(evidenceSource, "evidenceSource must not be null");
        List<WorkflowNode> subGraph = List.of(
                new ToolSubNode(delegate),
                new GuardSubNode(delegate, evidenceSource)
        );
        return new AgentNode(NAME, subGraph, subRuntime, AnalystAgentNode::lift);
    }

    /** 桥：只把分析素材袋提升到外层，子图内部不外泄。 */
    private static void lift(WorkflowContext subCtx, WorkflowContext outerCtx) {
        AnalysisPackage analysis = subCtx.getProduct(PRODUCT_ANALYSIS, AnalysisPackage.class);
        if (analysis != null) {
            outerCtx.putProduct(PRODUCT_ANALYSIS, analysis);
        }
    }

    /** 子图节点 1：调工具。toolPreface 由 delegate 侧保存（闭包/外层），不进袋。 */
    static final class ToolSubNode implements WorkflowNode {

        private final AnalystAgentDelegate delegate;

        ToolSubNode(AnalystAgentDelegate delegate) {
            this.delegate = delegate;
        }

        @Override
        public String name() {
            return StageName.TOOL.name();
        }

        @Override
        public NodeResult execute(WorkflowContext ctx) {
            delegate.invokeTool();
            return NodeResult.success();
        }
    }

    /** 子图节点 2：护栏 + prompt 拼装，产出 AnalysisPackage。无回边。 */
    static final class GuardSubNode implements WorkflowNode {

        private final AnalystAgentDelegate delegate;
        private final Supplier<EvidencePackage> evidenceSource;

        GuardSubNode(AnalystAgentDelegate delegate, Supplier<EvidencePackage> evidenceSource) {
            this.delegate = delegate;
            this.evidenceSource = evidenceSource;
        }

        @Override
        public String name() {
            return StageName.GUARD.name();
        }

        @Override
        public NodeResult execute(WorkflowContext ctx) {
            EvidencePackage evidence = evidenceSource.get();
            AnalystAgentDelegate.GuardOutcome outcome = delegate.guard(evidence);
            String finalPrompt = delegate.assemblePrompt(evidence);
            ctx.putProduct(PRODUCT_ANALYSIS, new AnalysisPackage(
                    finalPrompt,
                    outcome.skipLlm(),
                    outcome.clarifyBody()
            ));
            return NodeResult.success();
        }
    }
}
