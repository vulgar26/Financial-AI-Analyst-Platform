package com.travel.ai.agent.workflow;

import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.runtime.node.AgentNode;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.adapter;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.multiAgentAdapter;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.planJson;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.realPlanParser;
import static com.travel.ai.agent.workflow.MainChatWorkflowAdapterFixtures.state;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路 A：多 Agent 图编排路径 vs 单链路 runtime 路径的<b>批量参数化一致性回归</b>，
 * 并产出按 {@code agent=/scope=} 归因的结构化对比报告。
 *
 * <p>设计要点（守住的红线）：
 * <ul>
 *   <li>纯 test 层：不碰 main 代码 / contract 端点 / EvalChatService，不接 POST /api/v1/eval/chat。</li>
 *   <li>不引随机性：固定 plan、固定 doc、mock 工具——比的是<b>对 LLM 的确定性输入</b>
 *       （stageSeq / 归一化 finalPrompt / docs），不是 LLM 的随机输出。守住 eval「诚实的红」。</li>
 *   <li>双链路工厂 {@code adapter(true,...)} 与 {@code multiAgentAdapter(...)} wiring 对称，
 *       是天然的「前后对比」两条路。退化时报告能指到 case/agent/环节。</li>
 * </ul>
 *
 * <p>不纳入第一版：replan 回边 / 相关性裁判触发用的是 {@code thresholdAwareAdapter}，
 * 两条路构造不完全对称，硬比会产生由装置而非系统引起的漂移（一种说谎的红）。
 */
class MultiAgentParityRegressionTest {

    // ---------- Case 数据模型 ----------

    /** 一个参数化 case：固定 plan 形状下，双链路应得到一致的确定性输入。 */
    record Case(String name, String userMessage, boolean retrieve, boolean tool, boolean guard) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                new Case("full-pipeline", "price AAPL", true, true, true),
                new Case("stages-skipped", "plain answer", false, false, false),
                new Case("tool-skipped", "price AAPL", true, false, true),
                new Case("retrieve-only", "price AAPL", true, false, false),
                new Case("guard-after-tool", "price AAPL", false, true, true)
        );
    }

    // ---------- 报告模型 ----------

    /** 一条漂移记录：哪个环节、归到哪个 Agent、哪种粒度、哪类输入变了。 */
    record Drift(String stage, String agent, String scope, String kind) {
        @Override
        public String toString() {
            return kind + "@" + stage + "(" + agent + ":" + scope + ")";
        }
    }

    /** 单 case 的对比行。三项全 true 即该 case 双链路一致。 */
    record ParityRow(String caseName, boolean stageSeqMatch, boolean finalPromptMatch,
                     boolean docsMatch, List<Drift> driftAttribution) {
        boolean allMatch() {
            return stageSeqMatch && finalPromptMatch && docsMatch;
        }
    }

    // ---------- 每 case 硬断言（@ParameterizedTest，单 case 红即红）----------

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void multiAgentMatchesRuntimePath(Case c) {
        ParityRow row = compare(c);
        assertThat(row.allMatch())
                .as("case=%s 双链路确定性输入应一致，漂移归因=%s", c.name(), row.driftAttribution())
                .isTrue();
    }

    // ---------- 汇总：跑全集、构报告、断言全行全绿（重档核心）----------

    @Test
    void parityReportAcrossAllCasesIsGreen() {
        List<ParityRow> rows = cases().map(this::compare).toList();
        String report = renderReport(rows);
        System.out.println(report);

        // 报告对象即使全绿也已构建（证明装置自身工作）；任一行任一列红即定位到 case+列+Agent 归因。
        assertThat(rows).allSatisfy(row ->
                assertThat(row.allMatch())
                        .as("case=%s 漂移=%s", row.caseName(), row.driftAttribution())
                        .isTrue());
    }

    // ---------- 对比逻辑 ----------

    /**
     * 跑同一 case 的多 Agent 路径与单链路 runtime 路径，比对三项确定性输入。
     * 任一项 mismatch 时，按多 Agent 路径的 runtimeStageTraces 归因到 Agent+环节。
     */
    private ParityRow compare(Case c) {
        String plan = planJson(c.retrieve(), c.tool(), c.guard());

        WorkflowTurnState maCtx = state(c.userMessage());
        WorkflowTurnState rtCtx = state(c.userMessage());
        multiAgentAdapter(plan, realPlanParser()).runPreWriteWorkflow(maCtx);
        adapter(true, plan, realPlanParser()).runPreWriteWorkflow(rtCtx);

        boolean stageSeqMatch = stageSeq(maCtx).equals(stageSeq(rtCtx));
        boolean finalPromptMatch = java.util.Objects.equals(
                normalizeAsOf(maCtx.finalPromptForLlm), normalizeAsOf(rtCtx.finalPromptForLlm));
        boolean docsMatch = docTexts(maCtx).equals(docTexts(rtCtx));

        List<Drift> drifts = new ArrayList<>();
        if (!stageSeqMatch) {
            drifts.addAll(attribute(maCtx, "STAGE_SEQ"));
        }
        if (!finalPromptMatch) {
            drifts.addAll(attribute(maCtx, "FINAL_PROMPT"));
        }
        if (!docsMatch) {
            drifts.addAll(attribute(maCtx, "DOCS"));
        }
        return new ParityRow(c.name(), stageSeqMatch, finalPromptMatch, docsMatch, drifts);
    }

    /**
     * 归因：扫多 Agent 路径的 runtimeStageTraces，把每条带 agent= 的 trace 折成一条 Drift。
     * scope=agent 回答「锅在哪个 Agent」，scope=sub 回答「该 Agent 内哪个子步骤」（先粗后细，可下钻）。
     */
    private static List<Drift> attribute(WorkflowTurnState maCtx, String kind) {
        List<Drift> drifts = new ArrayList<>();
        for (StageTrace t : maCtx.runtimeStageTraces) {
            String agent = t.attrs().get(AgentNode.ATTR_AGENT);
            if (agent == null) {
                continue;
            }
            String scope = t.attrs().get(AgentNode.ATTR_SCOPE);
            drifts.add(new Drift(t.stage(), agent, scope, kind));
        }
        return drifts;
    }

    // ---------- 报告渲染 ----------

    private static String renderReport(List<ParityRow> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n=== 多 Agent 前后对比回归报告 (parity: multiAgent vs single-link runtime) ===\n");
        sb.append(String.format("%-18s | %-8s | %-7s | %-5s | %s%n",
                "case", "stageSeq", "prompt", "docs", "归因(kind@stage agent:scope)"));
        sb.append("-".repeat(90)).append('\n');
        for (ParityRow row : rows) {
            sb.append(String.format("%-18s | %-8s | %-7s | %-5s | %s%n",
                    row.caseName(),
                    mark(row.stageSeqMatch()),
                    mark(row.finalPromptMatch()),
                    mark(row.docsMatch()),
                    row.driftAttribution().isEmpty() ? "-" : row.driftAttribution()));
        }
        long red = rows.stream().filter(r -> !r.allMatch()).count();
        sb.append("-".repeat(90)).append('\n');
        sb.append(String.format("总计 %d case，绿 %d，红 %d%n", rows.size(), rows.size() - red, red));
        return sb.toString();
    }

    private static String mark(boolean ok) {
        return ok ? "OK" : "DRIFT";
    }

    // ---------- 取数 helper（与 MainChatWorkflowAdapterTest 同思路）----------

    private static List<String> stageSeq(WorkflowTurnState ctx) {
        return ctx.stageEvents.stream().map(e -> e.stage().name() + ":" + e.kind().name()).toList();
    }

    private static List<String> docTexts(WorkflowTurnState ctx) {
        return ctx.docs.stream().map(Document::getText).toList();
    }

    /** mock 工具内嵌的 as_of 墙钟时间戳是唯一不可控差异，归一化后再逐字比 prompt。 */
    private static String normalizeAsOf(String prompt) {
        return prompt == null ? null : prompt.replaceAll("as_of=[^\\n]+", "as_of=<ts>");
    }
}
