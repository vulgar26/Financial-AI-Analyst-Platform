package com.travel.ai.runtime.node;

import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 阶段 5 可观测归因测试：证明「出了问题能归因到哪个 Agent 的哪一步」。
 *
 * <p>这是对标简历那道「85% 准确率你怎么测、错了赖谁」答不出的题——多 Agent 把链路摊成两层后，
 * 报告必须既能按 {@code agent=} 分组（谁的锅），又能从汇总下钻到 {@code scope=sub} 的子步骤（哪一步）。
 * 故用合成子节点精确驱动成功/失败两条路径，断言 trace 标签齐全且原 attrs（replan 原因等）不丢。</p>
 *
 * <p>注意：归因走的是<em>独立的只读 trace 通道</em>，不破坏产物隔离——
 * 隔离断言见 {@link KnowledgeAgentNodeTest#isolation_outerContextSeesOnlyEvidenceNotInternalCounters()}。</p>
 */
class AgentNodeTraceAttributionTest {

    private static final String AGENT = "KNOWLEDGE";

    /** 合成子节点：按预设 NodeResult 返回，用来精确编排子图行为。 */
    private record ScriptedSubNode(String name, NodeResult result) implements WorkflowNode {
        @Override
        public NodeResult execute(WorkflowContext ctx) {
            return result;
        }
    }

    private static AgentNode agentWith(WorkflowNode... subNodes) {
        return new AgentNode(AGENT, List.of(subNodes), new LinearWorkflowRuntime(),
                (subCtx, outerCtx) -> { /* 本测试不关心产物提升，只验 trace 归因 */ });
    }

    private static WorkflowContext newOuter() {
        return new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat", "v1", "finance", "req-x", "conv-x", "问题"));
    }

    private static List<StageTrace> subTraces(WorkflowContext outer) {
        return outer.getStageTraces().stream()
                .filter(t -> AgentNode.SCOPE_SUB.equals(t.attrs().get(AgentNode.ATTR_SCOPE)))
                .toList();
    }

    @Test
    void everySubTraceTaggedWithAgentAndSubScope() {
        AgentNode node = agentWith(
                new ScriptedSubNode("RETRIEVE", NodeResult.success()),
                new ScriptedSubNode("JUDGE", NodeResult.success(Map.of("judge_outcome", "relevant"))));
        WorkflowContext outer = newOuter();

        node.execute(outer);

        List<StageTrace> subs = subTraces(outer);
        assertThat(subs).hasSize(2);
        assertThat(subs).allSatisfy(t -> {
            assertThat(t.attrs().get(AgentNode.ATTR_AGENT)).isEqualTo(AGENT);
            assertThat(t.attrs().get(AgentNode.ATTR_SCOPE)).isEqualTo(AgentNode.SCOPE_SUB);
        });
        assertThat(subs).extracting(StageTrace::stage).containsExactly("RETRIEVE", "JUDGE");
    }

    @Test
    void subTracePreservesOriginalAttrs() {
        // 子图原本带的 attrs（如 replan 原因）必须在复制时保留，否则下钻看不到「为什么重检」。
        AgentNode node = agentWith(
                new ScriptedSubNode("JUDGE",
                        NodeResult.redirectTo("RETRIEVE", Map.of("replan", "true", "replan_reason", "RAG_EMPTY"))));
        WorkflowContext outer = newOuter();

        node.execute(outer);

        StageTrace judge = subTraces(outer).get(0);
        assertThat(judge.attrs())
                .containsEntry("replan", "true")
                .containsEntry("replan_reason", "RAG_EMPTY")   // 原 attrs 不丢
                .containsEntry(AgentNode.ATTR_AGENT, AGENT)    // 叠加归因
                .containsEntry(AgentNode.ATTR_SCOPE, AgentNode.SCOPE_SUB);
    }

    @Test
    void summaryTraceTaggedWithAgentScopeOnSuccess() {
        // 引擎据 execute() 返回值记一条 scope=agent 的汇总 trace。
        AgentNode node = agentWith(new ScriptedSubNode("RETRIEVE", NodeResult.success()));

        NodeResult result = node.execute(newOuter());

        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(result.attrs())
                .containsEntry(AgentNode.ATTR_AGENT, AGENT)
                .containsEntry(AgentNode.ATTR_SCOPE, AgentNode.SCOPE_AGENT);
    }

    @Test
    void failurePathStillCarriesAgentAttribution() {
        // 关键：失败也要可归因。汇总 trace 带 agent/scope，子图失败那步的 sub trace 也在。
        AgentNode node = agentWith(
                new ScriptedSubNode("RETRIEVE", NodeResult.success()),
                new ScriptedSubNode("JUDGE", NodeResult.failed("BOOM", "judge exploded")));
        WorkflowContext outer = newOuter();

        NodeResult result = node.execute(outer);

        // 汇总：失败 + 可归因到本 Agent。
        assertThat(result.status()).isEqualTo(NodeStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(AgentNode.ERROR_CODE_SUBGRAPH_FAILED);
        assertThat(result.message()).contains("JUDGE");
        assertThat(result.attrs())
                .containsEntry(AgentNode.ATTR_AGENT, AGENT)
                .containsEntry(AgentNode.ATTR_SCOPE, AgentNode.SCOPE_AGENT);

        // 下钻：失败的子步骤照样带 agent/scope，且保留原 error。
        StageTrace failedSub = subTraces(outer).stream()
                .filter(t -> t.status() == NodeStatus.FAILED)
                .findFirst().orElseThrow();
        assertThat(failedSub.stage()).isEqualTo("JUDGE");
        assertThat(failedSub.errorCode()).isEqualTo("BOOM");
        assertThat(failedSub.attrs().get(AgentNode.ATTR_AGENT)).isEqualTo(AGENT);
    }

    @Test
    void differentAgentsAreDistinguishableInSameLedger() {
        // 两个 Agent 的 trace 落进同一外层账本时，靠 agent= 标签可区分（报告按 Agent 分组的前提）。
        WorkflowContext outer = newOuter();
        new AgentNode("KNOWLEDGE", List.of(new ScriptedSubNode("RETRIEVE", NodeResult.success())),
                new LinearWorkflowRuntime(), (s, o) -> {}).execute(outer);
        new AgentNode("ANALYST", List.of(new ScriptedSubNode("TOOL", NodeResult.success())),
                new LinearWorkflowRuntime(), (s, o) -> {}).execute(outer);

        assertThat(subTraces(outer))
                .extracting(t -> t.attrs().get(AgentNode.ATTR_AGENT))
                .containsExactly("KNOWLEDGE", "ANALYST");
    }
}
