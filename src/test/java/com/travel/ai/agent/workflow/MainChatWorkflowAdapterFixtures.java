package com.travel.ai.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.agent.MarketDataTool;
import com.travel.ai.agent.QueryRewriter;
import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.agent.guard.RetrievalRelevanceJudge;
import com.travel.ai.agent.plan.MainLinePlanProposer;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.agent.prompt.PromptAssemblyService;
import com.travel.ai.agent.retrieve.RetrieveService;
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.agent.tool.ToolInvocationService;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.finance.fundamentals.MockFundamentalsDataSource;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 共享测试夹具：构造 {@link MainChatWorkflowAdapter} 的几条对称链路（inline / 单链路 runtime / 多 Agent），
 * 以及 {@link WorkflowTurnState} 与 plan JSON。
 *
 * <p>抽到独立 helper 而非各测试类复制 40 行 wiring，是为了消灭「改一处漏一处」的同步债——
 * 双链路 parity 回归的全部意义就建立在「两条路除被测差异外 wiring 完全一致」之上，
 * 若 wiring 各写一份，一次漏改就会让 parity 断言比出装置差异而非系统差异（说谎的绿/红）。</p>
 *
 * <p>所有方法 package-private static，供同包测试类共用。不引随机性：plan 由 mock proposer 出固定 JSON、
 * 检索由 mock VectorStore 出固定 doc、工具走 {@link MockFundamentalsDataSource}。</p>
 */
final class MainChatWorkflowAdapterFixtures {

    private MainChatWorkflowAdapterFixtures() {
    }

    /** 单链路 runtime 路径（或 inline，取决于 runtimeEnabled）。 */
    static MainChatWorkflowAdapter adapter(boolean runtimeEnabled, String planJson, PlanParser policyParser) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(runtimeEnabled);
        return build(properties, planJson, policyParser,
                (q, docs) -> RetrievalRelevanceJudge.Verdict.relevant("stub-default-relevant"));
    }

    /**
     * 多 Agent 图编排路径工厂。刻意同时打开 workflow-runtime，用来证明优先级 multi-agent &gt; workflow-runtime。
     * 其余 wiring 与 {@link #adapter} 完全一致，从而支撑「同 query 同结果」的端到端一致性断言。
     */
    static MainChatWorkflowAdapter multiAgentAdapter(String planJson, PlanParser policyParser) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(true);
        properties.getMultiAgent().setEnabled(true);
        return build(properties, planJson, policyParser,
                (q, docs) -> RetrievalRelevanceJudge.Verdict.relevant("stub-default-relevant"));
    }

    /**
     * inline 路径(runtime disabled) + 自定义裁判：用于验证 inline 永远不咨询裁判、不回边。
     */
    static MainChatWorkflowAdapter inlineAdapterWithJudge(String planJson, RetrievalRelevanceJudge relevanceJudge) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(false);
        return build(properties, planJson, realPlanParser(), relevanceJudge);
    }

    /**
     * Replan 专用：VectorStore 命中与否取决于 SearchRequest 的 similarityThreshold。
     * 阈值 &gt;= {@code emptyAtOrAbove} 时返回空（模拟过严拦掉相关片段=假零命中），
     * 低于它时返回命中（模拟降级阈值后召回成功）。把 emptyAtOrAbove 设成超大值即可模拟「真缺失」。
     */
    static MainChatWorkflowAdapter thresholdAwareAdapter(String planJson, double emptyAtOrAbove) {
        return thresholdAwareAdapter(planJson, emptyAtOrAbove,
                (q, docs) -> RetrievalRelevanceJudge.Verdict.relevant("stub-default-relevant"));
    }

    static MainChatWorkflowAdapter thresholdAwareAdapter(String planJson, double emptyAtOrAbove,
                                                         RetrievalRelevanceJudge relevanceJudge) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(true);
        properties.getRag().setSimilarityThreshold(0.5);
        properties.getRag().setReplanSimilarityThreshold(0.35);

        PlanParser policyParser = realPlanParser();
        PlanService planService = planService(planJson, policyParser);

        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        when(queryRewriter.rewrite(any())).thenReturn(List.of("query-1"));
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenAnswer(invocation -> {
            SearchRequest req = invocation.getArgument(0);
            if (req.getSimilarityThreshold() >= emptyAtOrAbove) {
                return List.of();
            }
            return List.of(new Document("doc-1", "research hit", Map.of("source_name", "unit")));
        });
        RetrieveService retrieveService = new RetrieveService(queryRewriter, vectorStore);

        return assemble(properties, planService, retrieveService, relevanceJudge);
    }

    static PlanParser realPlanParser() {
        return new PlanParser(new ObjectMapper());
    }

    static WorkflowTurnState state(String userMessage) {
        return new WorkflowTurnState("conv-1", userMessage, "req-1");
    }

    static String planJson(boolean retrieve, boolean tool, boolean guard) {
        StringBuilder steps = new StringBuilder();
        int idx = 1;
        if (retrieve) {
            appendStep(steps, idx++, "RETRIEVE");
        }
        if (tool) {
            appendStep(steps, idx++, "TOOL");
        }
        if (guard) {
            appendStep(steps, idx++, "GUARD");
        }
        appendStep(steps, idx, "WRITE");
        return "{\"plan_version\":\"v1\",\"goal\":\"unit\","
                + "\"steps\":[" + steps + "],"
                + "\"constraints\":{\"max_steps\":8,\"total_timeout_ms\":60000,\"tool_timeout_ms\":3000},"
                + "\"notes\":\"unit\"}";
    }

    // ---------- 内部装配 ----------

    /** 标准（非 threshold-aware）链路：固定命中的 VectorStore。 */
    private static MainChatWorkflowAdapter build(
            AppAgentProperties properties,
            String planJson,
            PlanParser policyParser,
            RetrievalRelevanceJudge relevanceJudge
    ) {
        PlanService planService = planService(planJson, policyParser);

        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        when(queryRewriter.rewrite(any())).thenReturn(List.of("query-1"));
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("doc-1", "research hit", Map.of("source_name", "unit"))));
        RetrieveService retrieveService = new RetrieveService(queryRewriter, vectorStore);

        return assemble(properties, planService, retrieveService, relevanceJudge);
    }

    private static PlanService planService(String planJson, PlanParser policyParser) {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson(any(), any())).thenReturn(planJson);
        return new PlanService(
                proposer,
                new PlanParseCoordinator(policyParser, hint -> planJson),
                policyParser
        );
    }

    private static MainChatWorkflowAdapter assemble(
            AppAgentProperties properties,
            PlanService planService,
            RetrieveService retrieveService,
            RetrievalRelevanceJudge relevanceJudge
    ) {
        ToolInvocationService toolInvocationService = new ToolInvocationService(
                new MarketDataTool(new MockFundamentalsDataSource()),
                null,
                null
        );

        return new MainChatWorkflowAdapter(
                properties,
                new LinearWorkflowRuntime(),
                planService,
                retrieveService,
                toolInvocationService,
                new GuardDecisionService(),
                relevanceJudge,
                new PromptAssemblyService(null),
                () -> true,
                () -> 600,
                () -> "clarify",
                5,
                2,
                0.5
        );
    }

    private static void appendStep(StringBuilder steps, int idx, String stage) {
        if (steps.length() > 0) {
            steps.append(',');
        }
        steps.append("{\"step_id\":\"s")
                .append(idx)
                .append("\",\"stage\":\"")
                .append(stage)
                .append("\",\"instruction\":\"run ")
                .append(stage.toLowerCase())
                .append("\"}");
    }
}
