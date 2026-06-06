package com.travel.ai.agent.plan;

import com.travel.ai.plan.PlanParseCoordinator;
import com.travel.ai.plan.PlanParseException;
import com.travel.ai.plan.PlanParser;
import com.travel.ai.plan.PlanPhysicalStagePolicy;
import com.travel.ai.plan.PlanV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PLAN stage business service for proposal, fallback, parse repair, and parse meta assembly.
 *
 * <p>This service does not mutate TravelAgent turn state and does not emit SSE.
 * Pn2: it now also resolves the physical runtime stage flags from the effective
 * plan JSON, so the orchestrator no longer re-parses the plan a second time.</p>
 */
public final class PlanService {

    private static final Logger log = LoggerFactory.getLogger(PlanService.class);

    private final MainLinePlanProposer mainLinePlanProposer;
    private final PlanParseCoordinator planParseCoordinator;
    private final PlanParser planParser;

    public PlanService(MainLinePlanProposer mainLinePlanProposer,
                       PlanParseCoordinator planParseCoordinator,
                       PlanParser planParser) {
        this.mainLinePlanProposer = mainLinePlanProposer;
        this.planParseCoordinator = planParseCoordinator;
        this.planParser = planParser;
    }

    public PlanServiceResult plan(PlanServiceRequest request) {
        String userMessage = request != null ? request.userMessage() : null;
        String requestId = request != null ? request.requestId() : null;
        boolean planStageEnabled = request != null && request.planStageEnabled();

        String planJson;
        String planDraftSource;
        if (planStageEnabled) {
            try {
                planJson = mainLinePlanProposer.proposePlanJson(userMessage, requestId);
                planDraftSource = "llm";
                log.info("[stage] PLAN source=llm requestId={}", requestId);
            } catch (Exception e) {
                planJson = fallbackPlanJson("llm_failed");
                planDraftSource = "fallback_llm_error";
                log.warn("[stage] PLAN source=fallback_llm_error requestId={} error={}", requestId, e.toString());
            }
        } else {
            planJson = fallbackPlanJson("plan_stage_disabled");
            planDraftSource = "config_disabled";
            log.info("[stage] PLAN source=config_disabled requestId={}", requestId);
        }

        return enforceAppendixEPlanOrFallback(planJson, planDraftSource, requestId);
    }

    private PlanServiceResult enforceAppendixEPlanOrFallback(
            String planJson,
            String planDraftSource,
            String requestId
    ) {
        PlanParseCoordinator.Result first = planParseCoordinator.parseWithOptionalRepair(planJson);
        if (!first.failed()) {
            logPlanParseResolution(requestId, planDraftSource, first, "primary");
            return result(planDraftSource, first, "primary");
        }
        log.warn("[plan] draft_source={} plan_parse_outcome={} plan_parse_attempts={} requestId={} parse_error={}",
                planDraftSource,
                first.outcome(),
                first.attempts(),
                requestId,
                first.lastFailure() != null ? first.lastFailure().getMessage() : "");
        PlanParseCoordinator.Result second = planParseCoordinator.parseWithOptionalRepair(fallbackPlanJson("plan_parse_rejected"));
        if (!second.failed()) {
            logPlanParseResolution(requestId, planDraftSource, second, "fallback_template");
            return result(planDraftSource, second, "fallback_template");
        }
        String minimal = PlanParseCoordinator.DEFAULT_EVAL_PLAN_JSON.trim().replaceAll("\\s+", " ");
        PlanParseCoordinator.Result third = planParseCoordinator.parseWithOptionalRepair(minimal);
        if (!third.failed()) {
            logPlanParseResolution(requestId, planDraftSource, third, "builtin_minimal");
            return result(planDraftSource, third, "builtin_minimal");
        }
        throw new IllegalStateException("builtin default plan must parse");
    }

    private PlanServiceResult result(String planDraftSource, PlanParseCoordinator.Result r, String resolved) {
        return new PlanServiceResult(
                r.effectivePlanJson(),
                planDraftSource,
                r.outcome(),
                r.attempts(),
                resolved,
                resolvePhysicalStageFlags(r.effectivePlanJson())
        );
    }

    /**
     * Pn2：从 coordinator 产出的 effective plan JSON 推导物理阶段开关。
     * <p>effective JSON 已由 {@link PlanParseCoordinator} 校验/修复，正常应可解析；
     * 万一解析失败则兜底为「PLAN 后全跑」，与历史上主线 {@code physicalStageFlags(...)}
     * 解析失败即视为异常、不静默跳过任何物理阶段的语义对齐。</p>
     */
    private PlanPhysicalStagePolicy.PhysicalStageFlags resolvePhysicalStageFlags(String effectivePlanJson) {
        try {
            PlanV1 planV1 = planParser.parse(effectivePlanJson);
            return PlanPhysicalStagePolicy.resolve(planV1);
        } catch (PlanParseException e) {
            log.warn("[plan] physical_stage_flags_fallback effective plan unparsable: {}", e.toString());
            return PlanPhysicalStagePolicy.PhysicalStageFlags.allStagesAfterPlan();
        }
    }

    /**
     * 与主线历史日志口径一致，主线无 meta 时靠日志对账。
     */
    private static void logPlanParseResolution(
            String requestId,
            String planDraftSource,
            PlanParseCoordinator.Result r,
            String resolved
    ) {
        log.info("[plan] draft_source={} plan_parse_outcome={} plan_parse_attempts={} resolved={} requestId={}",
                planDraftSource,
                r.outcome(),
                r.attempts(),
                resolved,
                requestId);
    }

    static String jsonEscapeForNotes(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** Appendix-E compatible fallback: two-stage RETRIEVE->WRITE plan. */
    static String fallbackPlanJson(String rationale) {
        String n = jsonEscapeForNotes(rationale);
        return "{\"plan_version\":\"v1\",\"goal\":\"（配置或降级）\","
                + "\"steps\":["
                + "{\"step_id\":\"fb1\",\"stage\":\"RETRIEVE\",\"instruction\":\"检索与用户问题相关的知识片段。\"},"
                + "{\"step_id\":\"fb2\",\"stage\":\"WRITE\",\"instruction\":\"基于检索结果与工具观察生成回答。\"}"
                + "],"
                + "\"constraints\":{\"max_steps\":8,\"total_timeout_ms\":120000,\"tool_timeout_ms\":3000},"
                + "\"notes\":\"" + n + "\""
                + "}";
    }
}
