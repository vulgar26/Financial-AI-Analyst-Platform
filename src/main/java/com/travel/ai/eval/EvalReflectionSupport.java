package com.travel.ai.eval;

import com.travel.ai.eval.dto.EvalChatMeta;
import com.travel.ai.eval.dto.EvalChatRequest;
import com.travel.ai.eval.dto.EvalChatResponse;
import com.travel.ai.eval.dto.EvalGuardrailsCapability;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 评测用 <strong>Reflection / recovery</strong> 占位：写入 {@code meta.recovery_action} 与可选 {@code meta.self_check}，
 * 与受控 {@code replan_count}（有界 &lt;= 1，禁止无界循环 replan）正交；不调用额外 LLM。
 */
public final class EvalReflectionSupport {

    public static final String RECOVERY_NONE = "none";
    public static final String RECOVERY_ABORTED = "aborted";
    public static final String RECOVERY_CONTINUE = "continue";
    public static final String RECOVERY_SUGGEST_CLARIFY = "suggest_clarify";

    private EvalReflectionSupport() {
    }

    /**
     * 在响应发出前根据请求与当前 meta/错误码写入 reflection 字段，并按需点亮 {@code capabilities.guardrails.reflection}。
     */
    public static void apply(EvalChatResponse response, EvalChatRequest request, boolean reflectionMetaEnabled) {
        if (!reflectionMetaEnabled || response == null || response.getMeta() == null) {
            return;
        }
        EvalChatMeta meta = response.getMeta();
        String scenario = normalizeScenario(request);

        if (scenario != null) {
            switch (scenario) {
                case "self_check_ok" -> {
                    meta.setRecoveryAction(RECOVERY_CONTINUE);
                    meta.setSelfCheck(selfCheckOkStub());
                    patchGuardrailsReflection(response, true);
                    return;
                }
                case "recovery_suggest_clarify" -> {
                    meta.setRecoveryAction(RECOVERY_SUGGEST_CLARIFY);
                    meta.setSelfCheck(selfCheckFailedStub());
                    patchGuardrailsReflection(response, true);
                    return;
                }
                default -> {
                    // 未知取值：按 none 处理，避免静默吞掉拼写错误
                }
            }
        }

        if (EvalChatService.ERROR_CODE_AGENT_TOTAL_TIMEOUT.equals(response.getErrorCode())
                || "PARSE_ERROR".equals(response.getErrorCode())) {
            meta.setRecoveryAction(RECOVERY_ABORTED);
            return;
        }
        meta.setRecoveryAction(RECOVERY_NONE);
    }

    private static String normalizeScenario(EvalChatRequest request) {
        if (request == null) {
            return null;
        }
        String s = request.getEvalReflectionScenario();
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> selfCheckOkStub() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", "p0_reflection_stub_v1");
        m.put("passed", true);
        m.put("notes", "eval stub: one-shot self_check ok");
        return m;
    }

    private static Map<String, Object> selfCheckFailedStub() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", "p0_reflection_stub_v1");
        m.put("passed", false);
        m.put("risks", List.of("stub:inconsistent_with_retrieval_window"));
        return m;
    }

    private static void patchGuardrailsReflection(EvalChatResponse response, boolean reflection) {
        if (response.getCapabilities() == null || response.getCapabilities().getGuardrails() == null) {
            return;
        }
        EvalGuardrailsCapability g = response.getCapabilities().getGuardrails();
        g.setReflection(reflection);
    }
}
