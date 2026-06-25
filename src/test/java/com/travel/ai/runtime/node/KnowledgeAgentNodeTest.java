package com.travel.ai.runtime.node;

import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.retrieve.EvidencePackage;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Knowledge Agent 自治性测试：核心是证明「它自己决定 replan，外层只拿最终产物」。
 *
 * <p>用假 delegate 注入检索/裁判结果，断言子图的回边行为：零命中重查、不相关重查、
 * 命中且相关不重查、到上界即停、fail-safe 不重查。这就是「自治」的可测形态。</p>
 */
class KnowledgeAgentNodeTest {

    /** 记录每趟检索的入参，并按脚本返回预设证据袋。 */
    private static final class ScriptedDelegate implements KnowledgeAgentDelegate {
        final List<Integer> retrieveAttempts = new ArrayList<>();
        final List<Boolean> retrieveRelevanceFlags = new ArrayList<>();
        int judgeCalls = 0;

        private final List<EvidencePackage> retrieveScript;
        private final List<RetrievalRelevanceJudge.Verdict> judgeScript;

        ScriptedDelegate(List<EvidencePackage> retrieveScript,
                         List<RetrievalRelevanceJudge.Verdict> judgeScript) {
            this.retrieveScript = retrieveScript;
            this.judgeScript = judgeScript;
        }

        @Override
        public EvidencePackage retrieve(int attempt, boolean relevanceReplan) {
            retrieveAttempts.add(attempt);
            retrieveRelevanceFlags.add(relevanceReplan);
            int idx = Math.min(retrieveAttempts.size() - 1, retrieveScript.size() - 1);
            return retrieveScript.get(idx);
        }

        @Override
        public RetrievalRelevanceJudge.Verdict judge(List<Document> docs) {
            int idx = Math.min(judgeCalls, judgeScript.size() - 1);
            judgeCalls++;
            return judgeScript.get(idx);
        }
    }

    private static EvidencePackage withDocs(int n) {
        List<Document> docs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            docs.add(new Document("doc-" + i, "content-" + i, java.util.Map.of()));
        }
        return new EvidencePackage("u", List.of("q"), docs, "promptBase", "citation", 5L);
    }

    private static EvidencePackage empty() {
        return new EvidencePackage("u", List.of("q"), List.of(), "", "", 3L);
    }

    private static WorkflowContext run(AgentNode node) {
        WorkflowContext outer = new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat", "v1", "finance", "req-k", "conv-k", "问题"));
        node.execute(outer);
        return outer;
    }

    @Test
    void hitAndRelevant_doesNotReplan() {
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(withDocs(3)),
                List.of(RetrievalRelevanceJudge.Verdict.relevant("ok")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0); // 只检索一趟
        assertThat(delegate.judgeCalls).isEqualTo(1);
        EvidencePackage evidence = outer.getProduct(KnowledgeAgentNode.PRODUCT_EVIDENCE, EvidencePackage.class);
        assertThat(evidence).isNotNull();
        assertThat(evidence.docs()).hasSize(3);
    }

    @Test
    void emptyHits_replansOnceThenStops() {
        // 两趟都空：首检空→重查→仍空→到上界停。零命中不调裁判。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(empty(), empty()),
                List.of(RetrievalRelevanceJudge.Verdict.relevant("unused")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0, 1); // 检索两趟
        assertThat(delegate.judgeCalls).isEqualTo(0);                // 零命中从不调裁判
        EvidencePackage evidence = outer.getProduct(KnowledgeAgentNode.PRODUCT_EVIDENCE, EvidencePackage.class);
        assertThat(evidence.docs()).isEmpty();
    }

    @Test
    void emptyThenHit_replanRecovers() {
        // 首检空→重查→这次捞到且相关→停。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(empty(), withDocs(2)),
                List.of(RetrievalRelevanceJudge.Verdict.relevant("ok")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0, 1);
        // 第二趟非空但已到上界，不能再 replan → 不浪费 LLM 调裁判（与旧 stageGuard 同语义）。
        assertThat(delegate.judgeCalls).isEqualTo(0);
        EvidencePackage evidence = outer.getProduct(KnowledgeAgentNode.PRODUCT_EVIDENCE, EvidencePackage.class);
        assertThat(evidence.docs()).hasSize(2);
    }

    @Test
    void notRelevant_replansWithRewriteHint() {
        // 首检命中但不相关→换问法重检→第二趟相关→停。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(withDocs(2), withDocs(2)),
                List.of(
                        RetrievalRelevanceJudge.Verdict.notRelevant("off-topic"),
                        RetrievalRelevanceJudge.Verdict.relevant("ok")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0, 1);
        // 第二趟检索带「换问法」提示：relevanceReplan=true
        assertThat(delegate.retrieveRelevanceFlags).containsExactly(false, true);
        // 第二趟已到上界，不再调裁判（首检不相关触发的这次重检后，没有第三次行动机会）。
        assertThat(delegate.judgeCalls).isEqualTo(1);
    }

    @Test
    void notRelevant_butAtCap_stopsWithoutSecondJudge() {
        // maxReplans=0：不相关也不能重查，直接产出（自治上界生效）。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(withDocs(2)),
                List.of(RetrievalRelevanceJudge.Verdict.notRelevant("off-topic")));
        AgentNode node = KnowledgeAgentNode.of(delegate, new LinearWorkflowRuntime(), 0);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0); // 只检索一趟
        assertThat(delegate.judgeCalls).isEqualTo(0);             // 上界已满，连裁判都不调
        EvidencePackage evidence = outer.getProduct(KnowledgeAgentNode.PRODUCT_EVIDENCE, EvidencePackage.class);
        assertThat(evidence.docs()).hasSize(2);
    }

    @Test
    void judgeUnavailable_failSafeNoReplan() {
        // 裁判 fail-safe（available=false）：绝不触发 replan。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(withDocs(2)),
                List.of(RetrievalRelevanceJudge.Verdict.passThrough()));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(delegate.retrieveAttempts).containsExactly(0); // 不重查
        assertThat(delegate.judgeCalls).isEqualTo(1);
    }

    @Test
    void isolation_outerContextSeesOnlyEvidenceNotInternalCounters() {
        // 隔离铁证：外层 context 只拿到 evidence 产物，看不到子图的 replan_count 等内部 attrs。
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(empty(), empty()),
                List.of(RetrievalRelevanceJudge.Verdict.relevant("unused")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        WorkflowContext outer = run(node);

        assertThat(outer.getProduct(KnowledgeAgentNode.PRODUCT_EVIDENCE, EvidencePackage.class)).isNotNull();
        // 内部重查状态没有泄漏到外层 attrs。
        assertThat(outer.getAttrs()).doesNotContainKeys("replan_count", "relevance_replan");
    }

    @Test
    void subGraphSuccess_outerNodeSucceeds() {
        ScriptedDelegate delegate = new ScriptedDelegate(
                List.of(withDocs(1)),
                List.of(RetrievalRelevanceJudge.Verdict.relevant("ok")));
        AgentNode node = KnowledgeAgentNode.of(delegate);

        NodeResult result = node.execute(new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat", "v1", "finance", "req-k", "conv-k", "问题")));

        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(node.name()).isEqualTo(KnowledgeAgentNode.NAME);
    }
}
