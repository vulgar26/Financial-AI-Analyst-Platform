package com.travel.ai.agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.agent.MarketDataTool;
import com.travel.ai.agent.QueryRewriter;
import com.travel.ai.agent.guard.GuardDecisionService;
import com.travel.ai.agent.plan.MainLinePlanProposer;
import com.travel.ai.agent.plan.PlanService;
import com.travel.ai.agent.prompt.PromptAssemblyService;
import com.travel.ai.agent.retrieve.RetrieveService;
import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.agent.tool.ToolInvocationService;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.runtime.StageEventKind;
import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.trace.StageTrace;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    private static MainChatWorkflowAdapter adapter(boolean runtimeEnabled, String planJson, PlanParser policyParser) {
        AppAgentProperties properties = new AppAgentProperties();
        properties.getWorkflowRuntime().setEnabled(runtimeEnabled);

        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson(any(), any())).thenReturn(planJson);
        PlanService planService = new PlanService(
                proposer,
                new PlanParseCoordinator(policyParser, hint -> planJson),
                policyParser
        );

        QueryRewriter queryRewriter = mock(QueryRewriter.class);
        when(queryRewriter.rewrite(any())).thenReturn(List.of("query-1"));
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document("doc-1", "research hit", Map.of("source_name", "unit"))));
        RetrieveService retrieveService = new RetrieveService(queryRewriter, vectorStore);

        ToolInvocationService toolInvocationService = new ToolInvocationService(
                new MarketDataTool(),
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
                new PromptAssemblyService(null),
                () -> true,
                () -> 600,
                () -> "clarify",
                5,
                2
        );
    }

    private static PlanParser realPlanParser() {
        return new PlanParser(new ObjectMapper());
    }

    private static WorkflowTurnState state(String userMessage) {
        return new WorkflowTurnState("conv-1", userMessage, "req-1");
    }

    private static String planJson(boolean retrieve, boolean tool, boolean guard) {
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
