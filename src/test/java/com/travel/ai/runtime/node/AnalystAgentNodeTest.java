package com.travel.ai.runtime.node;

import com.travel.ai.agent.prompt.AnalysisPackage;
import com.travel.ai.agent.retrieve.EvidencePackage;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Analyst Agent 测试：核心是证明「只读资料袋、子图无回边、产出分析素材袋」。
 */
class AnalystAgentNodeTest {

    /** 记录 delegate 调用，并把 guard 看到的证据袋暴露出来断言「只读袋子」。 */
    private static final class RecordingDelegate implements AnalystAgentDelegate {
        int toolCalls = 0;
        int guardCalls = 0;
        int assembleCalls = 0;
        EvidencePackage guardSawEvidence;
        EvidencePackage assembleSawEvidence;

        private final GuardOutcome outcome;
        private final String prompt;

        RecordingDelegate(GuardOutcome outcome, String prompt) {
            this.outcome = outcome;
            this.prompt = prompt;
        }

        @Override
        public void invokeTool() {
            toolCalls++;
        }

        @Override
        public GuardOutcome guard(EvidencePackage evidence) {
            guardCalls++;
            guardSawEvidence = evidence;
            return outcome;
        }

        @Override
        public String assemblePrompt(EvidencePackage evidence) {
            assembleCalls++;
            assembleSawEvidence = evidence;
            return prompt;
        }
    }

    private static EvidencePackage evidence(int n) {
        Document d = new Document("doc", "content", java.util.Map.of());
        return new EvidencePackage("u", List.of("q"),
                n > 0 ? List.of(d) : List.of(), "promptBase", "citation", 5L);
    }

    private static WorkflowContext run(AgentNode node) {
        WorkflowContext outer = new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat", "v1", "finance", "req-a", "conv-a", "问题"));
        node.execute(outer);
        return outer;
    }

    @Test
    void runsToolThenGuard_producesAnalysisPackage() {
        EvidencePackage ev = evidence(1);
        RecordingDelegate delegate = new RecordingDelegate(
                new AnalystAgentDelegate.GuardOutcome(false, ""), "final-prompt-x");
        AgentNode node = AnalystAgentNode.of(delegate, () -> ev);

        WorkflowContext outer = run(node);

        assertThat(delegate.toolCalls).isEqualTo(1);
        assertThat(delegate.guardCalls).isEqualTo(1);
        assertThat(delegate.assembleCalls).isEqualTo(1);
        AnalysisPackage analysis = outer.getProduct(AnalystAgentNode.PRODUCT_ANALYSIS, AnalysisPackage.class);
        assertThat(analysis).isNotNull();
        assertThat(analysis.finalPromptForLlm()).isEqualTo("final-prompt-x");
        assertThat(analysis.skipLlm()).isFalse();
    }

    @Test
    void onlyReadsEvidencePackage_notKnowledgeInternals() {
        // 隔离铁证：guard/assemble 拿到的就是注入的那只袋子，没有别的通道。
        EvidencePackage ev = evidence(1);
        RecordingDelegate delegate = new RecordingDelegate(
                new AnalystAgentDelegate.GuardOutcome(false, ""), "p");
        AgentNode node = AnalystAgentNode.of(delegate, () -> ev);

        run(node);

        assertThat(delegate.guardSawEvidence).isSameAs(ev);
        assertThat(delegate.assembleSawEvidence).isSameAs(ev);
    }

    @Test
    void skipLlm_carriesClarifyBodyIntoPackage() {
        EvidencePackage ev = evidence(0); // 零命中
        RecordingDelegate delegate = new RecordingDelegate(
                new AnalystAgentDelegate.GuardOutcome(true, "请补充关键词"), "ignored-when-skip");
        AgentNode node = AnalystAgentNode.of(delegate, () -> ev);

        WorkflowContext outer = run(node);

        AnalysisPackage analysis = outer.getProduct(AnalystAgentNode.PRODUCT_ANALYSIS, AnalysisPackage.class);
        assertThat(analysis.skipLlm()).isTrue();
        assertThat(analysis.clarifyBody()).isEqualTo("请补充关键词");
    }

    @Test
    void subGraphHasNoBackEdge_toolRunsExactlyOnce() {
        // 无回边铁证：跑完工具只一次，不会被 guard 拉回重跑。
        EvidencePackage ev = evidence(1);
        RecordingDelegate delegate = new RecordingDelegate(
                new AnalystAgentDelegate.GuardOutcome(false, ""), "p");
        AgentNode node = AnalystAgentNode.of(delegate, () -> ev);

        run(node);

        assertThat(delegate.toolCalls).isEqualTo(1);
        assertThat(delegate.guardCalls).isEqualTo(1);
    }

    @Test
    void outerNodeSucceeds() {
        EvidencePackage ev = evidence(1);
        RecordingDelegate delegate = new RecordingDelegate(
                new AnalystAgentDelegate.GuardOutcome(false, ""), "p");
        AgentNode node = AnalystAgentNode.of(delegate, () -> ev);

        NodeResult result = node.execute(new WorkflowContext(WorkflowTask.of(
                "finance_analyst_chat", "v1", "finance", "req-a", "conv-a", "问题")));

        assertThat(result.status()).isEqualTo(NodeStatus.SUCCESS);
        assertThat(node.name()).isEqualTo(AnalystAgentNode.NAME);
    }
}
