package com.travel.ai.agent.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParseException;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.plan.PlanRepairModelPort;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlanServiceTest {

    private static final String VALID_WRITE_PLAN = """
            {
              "plan_version": "v1",
              "goal": "write only",
              "steps": [
                { "step_id": "s1", "stage": "WRITE", "instruction": "回答用户问题。" }
              ],
              "constraints": { "max_steps": 8, "total_timeout_ms": 60000, "tool_timeout_ms": 10000 }
            }
            """;

    @Test
    void planStageEnabled_proposerSuccess_recordsPrimarySuccess() {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson("question", "req-plan")).thenReturn(VALID_WRITE_PLAN);
        PlanService service = new PlanService(proposer, realCoordinator(hint -> VALID_WRITE_PLAN));

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", true));

        verify(proposer).proposePlanJson("question", "req-plan");
        assertThat(result.planJson()).isEqualTo(VALID_WRITE_PLAN.trim());
        assertThat(result.planDraftSource()).isEqualTo("llm");
        assertThat(result.planParseOutcome()).isEqualTo(PlanParseCoordinator.OUTCOME_SUCCESS);
        assertThat(result.planParseAttempts()).isEqualTo(1);
        assertThat(result.planParseResolved()).isEqualTo("primary");
    }

    @Test
    void planStageEnabled_proposerThrows_usesLlmFailedFallback() {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson("question", "req-plan")).thenThrow(new IllegalStateException("llm down"));
        PlanService service = new PlanService(proposer, realCoordinator(hint -> VALID_WRITE_PLAN));

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", true));

        assertThat(result.planDraftSource()).isEqualTo("fallback_llm_error");
        assertThat(result.planParseOutcome()).isEqualTo(PlanParseCoordinator.OUTCOME_SUCCESS);
        assertThat(result.planParseAttempts()).isEqualTo(1);
        assertThat(result.planParseResolved()).isEqualTo("primary");
        assertThat(result.planJson()).contains("\"notes\":\"llm_failed\"");
    }

    @Test
    void planStageDisabled_usesConfigDisabledFallback() {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        PlanService service = new PlanService(proposer, realCoordinator(hint -> VALID_WRITE_PLAN));

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", false));

        assertThat(result.planDraftSource()).isEqualTo("config_disabled");
        assertThat(result.planParseOutcome()).isEqualTo(PlanParseCoordinator.OUTCOME_SUCCESS);
        assertThat(result.planParseAttempts()).isEqualTo(1);
        assertThat(result.planParseResolved()).isEqualTo("primary");
        assertThat(result.planJson()).contains("\"notes\":\"plan_stage_disabled\"");
    }

    @Test
    void primaryParseFails_fallbackTemplateResolved() {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson("question", "req-plan")).thenReturn("{\"plan_version\":\"v1\"}");
        PlanService service = new PlanService(proposer, realCoordinator(hint -> "{\"plan_version\":\"v1\",\"steps\":[]}"));

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", true));

        assertThat(result.planDraftSource()).isEqualTo("llm");
        assertThat(result.planParseOutcome()).isEqualTo(PlanParseCoordinator.OUTCOME_SUCCESS);
        assertThat(result.planParseAttempts()).isEqualTo(1);
        assertThat(result.planParseResolved()).isEqualTo("fallback_template");
        assertThat(result.planJson()).contains("\"notes\":\"plan_parse_rejected\"");
    }

    @Test
    void primaryAndFallbackTemplateFail_builtinMinimalResolved() {
        MainLinePlanProposer proposer = mock(MainLinePlanProposer.class);
        when(proposer.proposePlanJson("question", "req-plan")).thenReturn("{\"plan_version\":\"v1\"}");
        PlanParseCoordinator.Result failed = new PlanParseCoordinator.Result(
                2,
                PlanParseCoordinator.OUTCOME_FAILED,
                new PlanParseException("invalid"),
                null
        );
        PlanParseCoordinator.Result success = new PlanParseCoordinator.Result(
                1,
                PlanParseCoordinator.OUTCOME_SUCCESS,
                null,
                PlanParseCoordinator.DEFAULT_EVAL_PLAN_JSON.trim().replaceAll("\\s+", " ")
        );
        PlanService service = new PlanService(
                proposer,
                new SequentialPlanParseCoordinator(failed, failed, success)
        );

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", true));

        assertThat(result.planDraftSource()).isEqualTo("llm");
        assertThat(result.planParseOutcome()).isEqualTo(PlanParseCoordinator.OUTCOME_SUCCESS);
        assertThat(result.planParseAttempts()).isEqualTo(1);
        assertThat(result.planParseResolved()).isEqualTo("builtin_minimal");
        assertThat(result.planJson()).isEqualTo(PlanParseCoordinator.DEFAULT_EVAL_PLAN_JSON.trim().replaceAll("\\s+", " "));
    }

    @Test
    void fallbackPlanJson_escapesNotes() throws Exception {
        String plan = PlanService.fallbackPlanJson("a\"b\\c\n d\r e");

        assertThat(plan).contains("\"notes\":\"a\\\"b\\\\c  d  e\"");
        realParser().parse(plan);
    }

    @Test
    void resultPlanJson_remainsParsable() throws Exception {
        PlanService service = new PlanService(
                mock(MainLinePlanProposer.class),
                realCoordinator(hint -> VALID_WRITE_PLAN)
        );

        PlanServiceResult result = service.plan(new PlanServiceRequest("question", "req-plan", false));

        assertThat(realParser().parse(result.planJson()).steps()).hasSize(2);
    }

    private static PlanParseCoordinator realCoordinator(PlanRepairModelPort repairModelPort) {
        return new PlanParseCoordinator(realParser(), repairModelPort);
    }

    private static PlanParser realParser() {
        return new PlanParser(new ObjectMapper());
    }

    private static final class SequentialPlanParseCoordinator extends PlanParseCoordinator {

        private final Queue<Result> results = new ArrayDeque<>();

        private SequentialPlanParseCoordinator(Result... results) {
            super(realParser(), hint -> VALID_WRITE_PLAN);
            this.results.addAll(java.util.List.of(results));
        }

        @Override
        public Result parseWithOptionalRepair(String planRaw) {
            Result result = results.poll();
            if (result == null) {
                throw new AssertionError("unexpected parse call for " + planRaw);
            }
            return result;
        }
    }
}
