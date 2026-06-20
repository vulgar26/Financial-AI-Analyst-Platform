package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalChatRequest;

import java.util.List;
import java.util.Locale;

/**
 * Day7：评测用 RAG 门控场景（P0 不启用 score 阈值，仅用结构化 {@code low_confidence_reasons[]} 说明）。
 * <p>
 * 对齐 {@code plans/travel-ai-upgrade.md}：空命中 / 低置信时 {@code meta.low_confidence=true}，{@code behavior} 以 clarify 结束。
 */
public final class EvalRagGateScenarios {

    public static final String ERROR_CODE_RETRIEVE_EMPTY = "RETRIEVE_EMPTY";
    public static final String ERROR_CODE_RETRIEVE_LOW_CONFIDENCE = "RETRIEVE_LOW_CONFIDENCE";

    /** rag/empty：零命中 */
    public static final List<String> REASONS_EMPTY_HITS = List.of(
            "retrieve_hit_count=0",
            "rag_empty_hits",
            "p0_score_threshold_gate_disabled"
    );

    /** rag/low_conf：P0 不按 score 阈值，仅评测 stub 低置信 */
    public static final List<String> REASONS_LOW_CONFIDENCE = List.of(
            "p0_low_confidence_without_score_threshold",
            "heuristic_low_confidence_stub",
            "dataset_tag:rag/low_conf"
    );

    /**
     * rag/replan_after_empty：首检索假零命中→受控回边降阈值重查→命中。
     * 用于回归「有界 replan」：{@code replan_count=1}、{@code stage_order} 体现 RETRIEVE 重跑。
     */
    public static final List<String> REASONS_REPLAN_AFTER_EMPTY = List.of(
            "first_pass_empty_hits",
            "replan_lowered_similarity_threshold",
            "second_pass_retrieved"
    );

    /** 业务侧低置信：短 query / 指代不明 等 */
    public static final List<String> REASONS_LOW_CONFIDENCE_BUSINESS = List.of(
            "heuristic_low_confidence_business",
            "clarify_missing_or_ambiguous_slots",
            "p0_score_threshold_gate_disabled"
    );

    private EvalRagGateScenarios() {
    }

    /**
     * @return {@code null} 表示不触发门控
     */
    public static Kind resolve(EvalChatRequest request) {
        String raw = request.getEvalRagScenario();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if ("empty".equals(s) || "empty_hits".equals(s) || "rag_empty".equals(s)) {
            return Kind.EMPTY_HITS;
        }
        if ("low_conf".equals(s) || "low_confidence".equals(s) || "rag_low_conf".equals(s)) {
            return Kind.LOW_CONFIDENCE;
        }
        if ("replan_after_empty".equals(s) || "empty_then_replan".equals(s) || "rag_replan".equals(s)) {
            return Kind.EMPTY_THEN_REPLAN;
        }
        return null;
    }

    public enum Kind {
        EMPTY_HITS,
        LOW_CONFIDENCE,
        EMPTY_THEN_REPLAN
    }
}
