package com.travel.ai.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.node.AgentNode;
import com.travel.ai.runtime.node.AnalystAgentNode;
import com.travel.ai.runtime.node.KnowledgeAgentNode;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.adapter;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.inlineAdapterWithJudge;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.multiAgentAdapter;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.planJson;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.realPlanParser;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.state;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.thresholdAwareAdapter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainChatWorkflowAdapterTest {

    @Test
    void flagOff_runsLegacyStagesInSameOrder() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(false, planJson(true, true, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START",
                        "PLAN:END",
                        "RETRIEVE:START",
                        "RETRIEVE:END",
                        "TOOL:START",
                        "TOOL:END",
                        "GUARD:START",
                        "GUARD:END"
                );
        assertThat(ctx.workflowRuntimePath).isFalse();
        assertThat(ctx.runtimeStageTraces).isEmpty();
        assertThat(ctx.finalPromptForLlm).contains("BEGIN_TOOL_DATA", "research hit", "\"stage\":\"RETRIEVE\"");
    }

    @Test
    void flagOn_runsRuntimeStagesInSameOrder() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, true, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START",
                        "PLAN:END",
                        "RETRIEVE:START",
                        "RETRIEVE:END",
                        "TOOL:START",
                        "TOOL:END",
                        "GUARD:START",
                        "GUARD:END"
                );
        assertThat(ctx.workflowRuntimePath).isTrue();
    }

    @Test
    void flagOn_capturesRuntimeStageTraces() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, false, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::stage)
                .containsExactly("PLAN", "RETRIEVE", "TOOL", "GUARD");
    }

    @Test
    void flagOn_convertsFailedTraceToException() {
        WorkflowTurnState ctx = state("price AAPL");
        PlanParser failingPhysicalPolicyParser = new PlanParser(new ObjectMapper()) {
            @Override
            public com.travel.ai.plan.PlanV1 parse(String raw) throws com.travel.ai.plan.PlanParseException {
                throw new com.travel.ai.plan.PlanParseException("boom");
            }
        };
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, true, true), failingPhysicalPolicyParser);

        assertThatThrownBy(() -> adapter.runPreWriteWorkflow(ctx))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("workflow runtime node failed: PLAN");

        assertThat(ctx.runtimeStageTraces)
                .extracting(StageTrace::stage)
                .contains("PLAN");
    }

    @Test
    void flagOn_doesNotDuplicateStageEventsForSkippedStages() {
        WorkflowTurnState ctx = state("plain answer");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(false, false, false), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly("PLAN:START", "PLAN:END", "RETRIEVE:SKIP", "TOOL:SKIP", "GUARD:SKIP");
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.RETRIEVE).count()).isEqualTo(1);
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.TOOL).count()).isEqualTo(1);
        assertThat(ctx.stageEvents.stream().filter(e -> e.stage() == StageName.GUARD).count()).isEqualTo(1);
        assertThat(ctx.stageEvents)
                .filteredOn(e -> e.kind() == StageEventKind.SKIP)
                .extracting(e -> e.attrs().get("reason"))
                .containsOnly("skipped_by_plan");
    }

    @Test
    void flagOn_capturesRuntimeToolTraceOnce() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, true, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.runtimeToolTraces).hasSize(1);
        assertThat(ctx.runtimeToolTraces.get(0).toolName()).isEqualTo("market_data");
    }

    @Test
    void flagOn_doesNotCaptureRuntimeToolTraceWhenToolSkipped() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, false, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.runtimeToolTraces).isEmpty();
        assertThat(ctx.finalPromptForLlm).doesNotContain("BEGIN_TOOL_DATA");
    }

    @Test
    void flagOn_doesNotDuplicatePolicyEvents() {
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = adapter(true, planJson(true, true, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        Set<String> unique = new LinkedHashSet<>();
        for (PolicyEvent e : ctx.policyEvents) {
            unique.add(e.policyType() + "|" + e.stage() + "|" + e.ruleId());
        }
        assertThat(ctx.policyEvents).hasSize(unique.size());
        assertThat(ctx.policyEvents)
                .extracting(PolicyEvent::policyType)
                .contains("tool_stage", "rag_gate", "finance_guard");
    }

    @Test
    void flagOff_buildsStableNonBlankFinalPromptWhenToolSkipped() {
        WorkflowTurnState ctx = state("plain answer");
        MainChatWorkflowAdapter adapter = adapter(false, planJson(true, false, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.finalPromptForLlm).isNotBlank();
        assertThat(ctx.finalPromptForLlm).contains(
                "research hit",
                "\"plan_version\":\"v1\"",
                "\"stage\":\"RETRIEVE\""
        );
        assertThat(ctx.finalPromptForLlm).doesNotContain("BEGIN_TOOL_DATA");
    }

    @Test
    void flagOn_replanRetriesRetrieveOnceWithLoweredThresholdWhenFirstPassEmpty() {
        WorkflowTurnState ctx = state("obscure question");
        // 高阈值(0.5)首检索零命中、降级阈值(0.35)命中：证明 GUARD→RETRIEVE 回边发生且重查放宽了阈值。
        MainChatWorkflowAdapter adapter = thresholdAwareAdapter(planJson(true, false, true), 0.5);

        adapter.runPreWriteWorkflow(ctx);

        // RETRIEVE 跑了两遍（首检索 + replan 重查），回边后 TOOL→GUARD 整段重跑。
        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START", "PLAN:END",
                        "RETRIEVE:START", "RETRIEVE:END",
                        "TOOL:SKIP",
                        "GUARD:START", "GUARD:END",
                        "RETRIEVE:START", "RETRIEVE:END",
                        "TOOL:SKIP",
                        "GUARD:START", "GUARD:END"
                );
        assertThat(ctx.retrievalReplanCount).isEqualTo(1);
        // 重查命中，最终不该落空澄清。
        assertThat(ctx.skipLlmForEmptyHits).isFalse();
        assertThat(ctx.docs).isNotEmpty();
    }

    @Test
    void flagOn_replanFiresAtMostOnceThenFallsBackToClarifyWhenStillEmpty() {
        WorkflowTurnState ctx = state("truly absent topic");
        // 任何阈值都零命中(emptyAtOrAbove=0.0)：replan 探一次后仍空，必须停在 1 次、回边不得无限循环，最终落澄清。
        MainChatWorkflowAdapter adapter = thresholdAwareAdapter(planJson(true, false, true), 0.0);

        adapter.runPreWriteWorkflow(ctx);

        long retrieveStarts = ctx.stageEvents.stream()
                .filter(e -> e.stage() == StageName.RETRIEVE && e.kind() == StageEventKind.START)
                .count();
        assertThat(retrieveStarts).isEqualTo(2); // 首检索 + 恰好一次重查
        assertThat(ctx.retrievalReplanCount).isEqualTo(1);
        // 重查仍空：第二轮 GUARD 落地澄清，不再 replan。
        assertThat(ctx.skipLlmForEmptyHits).isTrue();
        assertThat(ctx.docs).isEmpty();
    }

    // ---------- 第二刀：语义相关性裁判触发回边「换问法重检」 ----------

    @Test
    void flagOn_relevanceJudgeNotRelevantTriggersRewriteReplanOnce() {
        WorkflowTurnState ctx = state("贵州茅台三季度毛利率");
        // emptyAtOrAbove=999：任何阈值都命中(docs 永远非空)，把判别权交给相关性裁判。
        // 裁判判 not-relevant → 应回边「换问法」重检一次。
        RetrievalRelevanceJudge notRelevant =
                (q, docs) -> RetrievalRelevanceJudge.Verdict.notRelevant("片段是旅游攻略，与财报无关");
        MainChatWorkflowAdapter adapter = thresholdAwareAdapter(planJson(true, false, true), 999.0, notRelevant);

        adapter.runPreWriteWorkflow(ctx);

        // RETRIEVE 跑两遍（首检索 + 相关性 replan 重检），回边后 TOOL→GUARD 整段重跑。
        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START", "PLAN:END",
                        "RETRIEVE:START", "RETRIEVE:END",
                        "TOOL:SKIP",
                        "GUARD:START", "GUARD:END",
                        "RETRIEVE:START", "RETRIEVE:END",
                        "TOOL:SKIP",
                        "GUARD:START", "GUARD:END"
                );
        assertThat(ctx.retrievalReplanCount).isEqualTo(1);
        // 一次性提示前缀已被消费，避免污染后续判断。
        assertThat(ctx.relevanceReplanRequested).isFalse();
        // 重检命中、未落空澄清。
        assertThat(ctx.skipLlmForEmptyHits).isFalse();
        assertThat(ctx.docs).isNotEmpty();
    }

    @Test
    void flagOn_relevanceJudgeUnavailableDoesNotReplan_failSafePassThrough() {
        WorkflowTurnState ctx = state("贵州茅台三季度毛利率");
        // 裁判 LLM 不可用(available=false)：fail-safe 放行，绝不触发 replan
        // ——否则一次网络抖动就能让正常请求空转重查，把外部不稳定变成功能 bug。
        RetrievalRelevanceJudge unavailable =
                (q, docs) -> RetrievalRelevanceJudge.Verdict.passThrough();
        MainChatWorkflowAdapter adapter = thresholdAwareAdapter(planJson(true, false, true), 999.0, unavailable);

        adapter.runPreWriteWorkflow(ctx);

        assertThat(ctx.stageEvents)
                .extracting(e -> e.stage().name() + ":" + e.kind().name())
                .containsExactly(
                        "PLAN:START", "PLAN:END",
                        "RETRIEVE:START", "RETRIEVE:END",
                        "TOOL:SKIP",
                        "GUARD:START", "GUARD:END"
                );
        assertThat(ctx.retrievalReplanCount).isEqualTo(0);
        assertThat(ctx.relevanceReplanRequested).isFalse();
    }

    @Test
    void flagOn_relevanceJudgeRelevantPassesThroughWithoutReplan() {
        WorkflowTurnState ctx = state("贵州茅台三季度毛利率");
        RetrievalRelevanceJudge relevant =
                (q, docs) -> RetrievalRelevanceJudge.Verdict.relevant("片段含相关财报事实");
        MainChatWorkflowAdapter adapter = thresholdAwareAdapter(planJson(true, false, true), 999.0, relevant);

        adapter.runPreWriteWorkflow(ctx);

        long retrieveStarts = ctx.stageEvents.stream()
                .filter(e -> e.stage() == StageName.RETRIEVE && e.kind() == StageEventKind.START)
                .count();
        assertThat(retrieveStarts).isEqualTo(1); // 相关 → 不回边
        assertThat(ctx.retrievalReplanCount).isEqualTo(0);
    }

    @Test
    void flagOff_inlinePathNeverConsultsRelevanceJudge() {
        WorkflowTurnState ctx = state("贵州茅台三季度毛利率");
        // inline 直线基线无回边可承接 redirect：即便裁判判 not-relevant 也不该回边（能力开关语义），
        // 且为省一次 LLM 调用，inline 路径根本不咨询裁判。
        java.util.concurrent.atomic.AtomicInteger judgeCalls = new java.util.concurrent.atomic.AtomicInteger();
        RetrievalRelevanceJudge spy = (q, docs) -> {
            judgeCalls.incrementAndGet();
            return RetrievalRelevanceJudge.Verdict.notRelevant("would-replan-if-runtime");
        };
        // runtimeEnabled=false → inline 路径。
        MainChatWorkflowAdapter adapter = inlineAdapterWithJudge(planJson(true, false, true), spy);

        adapter.runPreWriteWorkflow(ctx);

        assertThat(judgeCalls.get()).isZero();
        assertThat(ctx.retrievalReplanCount).isEqualTo(0);
    }

    // ---------- Phase 4：外层图编排路径 vs 现有 runtime 路径，端到端一致 ----------

    @Test
    void multiAgent_matchesRuntimePath_fullPipeline() {
        WorkflowTurnState maCtx = state("price AAPL");
        WorkflowTurnState rtCtx = state("price AAPL");
        multiAgentAdapter(planJson(true, true, true), realPlanParser()).runPreWriteWorkflow(maCtx);
        adapter(true, planJson(true, true, true), realPlanParser()).runPreWriteWorkflow(rtCtx);

        assertThat(stageSeq(maCtx)).isEqualTo(stageSeq(rtCtx));
        // 唯一不可控差异是 mock 工具内嵌的 as_of 墙钟时间戳（两条路径各自调一次工具，时间戳差几微秒）；
        // 归一化后做逐字相等，保留「除不可避免的墙钟外，两路 prompt 完全一致」的强断言。
        assertThat(normalizeAsOf(maCtx.finalPromptForLlm)).isEqualTo(normalizeAsOf(rtCtx.finalPromptForLlm));
        assertThat(maCtx.skipLlmForEmptyHits).isEqualTo(rtCtx.skipLlmForEmptyHits);
        assertThat(maCtx.docs).extracting(Document::getText).isEqualTo(rtCtx.docs.stream().map(Document::getText).toList());
    }

    private static String normalizeAsOf(String prompt) {
        return prompt == null ? null : prompt.replaceAll("as_of=[^\\n]+", "as_of=<ts>");
    }

    @Test
    void multiAgent_matchesRuntimePath_whenStagesSkipped() {
        WorkflowTurnState maCtx = state("plain answer");
        WorkflowTurnState rtCtx = state("plain answer");
        multiAgentAdapter(planJson(false, false, false), realPlanParser()).runPreWriteWorkflow(maCtx);
        adapter(true, planJson(false, false, false), realPlanParser()).runPreWriteWorkflow(rtCtx);

        assertThat(stageSeq(maCtx)).isEqualTo(stageSeq(rtCtx));
        assertThat(stageSeq(maCtx))
                .containsExactly("PLAN:START", "PLAN:END", "RETRIEVE:SKIP", "TOOL:SKIP", "GUARD:SKIP");
        assertThat(maCtx.finalPromptForLlm).isEqualTo(rtCtx.finalPromptForLlm);
    }

    @Test
    void multiAgent_matchesRuntimePath_whenToolSkipped() {
        WorkflowTurnState maCtx = state("price AAPL");
        WorkflowTurnState rtCtx = state("price AAPL");
        multiAgentAdapter(planJson(true, false, true), realPlanParser()).runPreWriteWorkflow(maCtx);
        adapter(true, planJson(true, false, true), realPlanParser()).runPreWriteWorkflow(rtCtx);

        assertThat(stageSeq(maCtx)).isEqualTo(stageSeq(rtCtx));
        assertThat(maCtx.finalPromptForLlm).isEqualTo(rtCtx.finalPromptForLlm);
        assertThat(maCtx.finalPromptForLlm).doesNotContain("BEGIN_TOOL_DATA");
    }

    @Test
    void multiAgent_takesPriorityOverWorkflowRuntime() {
        // 同时开 multi-agent 与 workflow-runtime：应走 multi-agent（优先级 multi-agent > workflow-runtime）。
        WorkflowTurnState ctx = state("price AAPL");
        MainChatWorkflowAdapter adapter = multiAgentAdapter(planJson(true, true, true), realPlanParser());

        adapter.runPreWriteWorkflow(ctx);

        // 多 Agent 路径自己合成 stageEvents，且不把 KNOWLEDGE/ANALYST 当作 StageName 混进来。
        assertThat(stageSeq(ctx)).containsExactly(
                "PLAN:START", "PLAN:END",
                "RETRIEVE:START", "RETRIEVE:END",
                "TOOL:START", "TOOL:END",
                "GUARD:START", "GUARD:END"
        );
        assertThat(ctx.workflowRuntimePath).isTrue();
    }

    private static List<String> stageSeq(WorkflowTurnState ctx) {
        return ctx.stageEvents.stream().map(e -> e.stage().name() + ":" + e.kind().name()).toList();
    }

    // ---------- Phase 5：多 Agent 路径的 trace 可归因（哪个 Agent 出的问题） ----------

    @Test
    void multiAgent_runtimeTracesAreAttributableToEachAgent() {
        // 命脉断言：多 Agent 路径记账后，每条 runtime trace 都能归因到某个 Agent，
        // 且子步骤落在正确的 Agent 边界内（RETRIEVE/JUDGE∈KNOWLEDGE，TOOL/GUARD∈ANALYST）。
        // 这正是对标简历「85% 准确率，错了赖哪个 Agent」答不出的那题的可测形态。
        WorkflowTurnState ctx = state("price AAPL");
        multiAgentAdapter(planJson(true, true, true), realPlanParser()).runPreWriteWorkflow(ctx);

        // 每条 trace 都带 agent= 归因（无裸 trace）。
        assertThat(ctx.runtimeStageTraces).isNotEmpty();
        assertThat(ctx.runtimeStageTraces).allSatisfy(t ->
                assertThat(t.attrs().get(AgentNode.ATTR_AGENT)).isIn(KnowledgeAgentNode.NAME, AnalystAgentNode.NAME));

        // 边界正确：检索/裁判归 Knowledge，工具/护栏归 Analyst。
        assertThat(agentOf(ctx, StageName.RETRIEVE.name())).isEqualTo(KnowledgeAgentNode.NAME);
        assertThat(agentOf(ctx, StageName.TOOL.name())).isEqualTo(AnalystAgentNode.NAME);
        assertThat(agentOf(ctx, StageName.GUARD.name())).isEqualTo(AnalystAgentNode.NAME);

        // 两层粒度并存：既有 scope=agent 的汇总，又有 scope=sub 的子步骤（报告可下钻）。
        assertThat(ctx.runtimeStageTraces).anySatisfy(t ->
                assertThat(t.attrs().get(AgentNode.ATTR_SCOPE)).isEqualTo(AgentNode.SCOPE_AGENT));
        assertThat(ctx.runtimeStageTraces).anySatisfy(t ->
                assertThat(t.attrs().get(AgentNode.ATTR_SCOPE)).isEqualTo(AgentNode.SCOPE_SUB));
    }

    @Test
    void multiAgent_attributionFlowsThroughToEvalReport() {
        // Plan 第三步钉死：RuntimeEvalTraceMapper 透传 attrs → agent= 自动进 eval 报告 DTO。
        WorkflowTurnState ctx = state("price AAPL");
        multiAgentAdapter(planJson(true, true, true), realPlanParser()).runPreWriteWorkflow(ctx);

        StageTrace retrieveSub = ctx.runtimeStageTraces.stream()
                .filter(t -> StageName.RETRIEVE.name().equals(t.stage()))
                .findFirst().orElseThrow();
        var dto = com.travel.ai.eval.RuntimeEvalTraceMapper.toEvalStageTrace(retrieveSub);

        assertThat(dto.getAttrs()).containsEntry(AgentNode.ATTR_AGENT, KnowledgeAgentNode.NAME);
    }

    @Test
    void runtimePath_tracesHaveNoAgentAttribution() {
        // 对照：单链路 runtime 路径的 trace 不带 agent=（归因是多 Agent 路径独有的差异化）。
        WorkflowTurnState ctx = state("price AAPL");
        adapter(true, planJson(true, true, true), realPlanParser()).runPreWriteWorkflow(ctx);

        assertThat(ctx.runtimeStageTraces).isNotEmpty();
        assertThat(ctx.runtimeStageTraces).allSatisfy(t ->
                assertThat(t.attrs()).doesNotContainKey(AgentNode.ATTR_AGENT));
    }

    /** 取首条 stage==name 的 trace 的 agent 归因标签。 */
    private static String agentOf(WorkflowTurnState ctx, String stage) {
        return ctx.runtimeStageTraces.stream()
                .filter(t -> stage.equals(t.stage()))
                .map(t -> t.attrs().get(AgentNode.ATTR_AGENT))
                .findFirst().orElseThrow(() -> new AssertionError("no trace for stage " + stage));
    }
}

