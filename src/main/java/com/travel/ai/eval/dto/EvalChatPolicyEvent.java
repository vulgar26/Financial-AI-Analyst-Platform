package com.travel.ai.eval.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.Map;

/**
 * 评测 {@code meta.policy_events[]} 单条：结构化决策轨迹（不含 query 原文等敏感字段）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EvalChatPolicyEvent {

    /**
     * 策略族：如 {@code safety_gate}、{@code query_safety}、{@code rag_gate}、{@code behavior_policy}、{@code tool_stage}。
     */
    private String policyType;

    /**
     * 流水线锚点：如 {@code pre_plan}（plan 解析前）、{@code post_plan}、{@code post_retrieve}、{@code tool}。
     */
    private String stage;

    /**
     * 与顶层 {@code behavior} 对齐的决策：{@code deny|clarify|tool|answer} 等。
     */
    private String behavior;

    /**
     * 可归因的稳定 id（如 SafetyGate {@code ruleId}、或 {@code safety:…} 原因码前缀）。
     */
    private String ruleId;

    /** 与顶层 {@code error_code} 对齐；clarify 等场景可为空。 */
    private String errorCode;

    private String decision;

    private String severity;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, String> attrs;

    public String getPolicyType() {
        return policyType;
    }

    public void setPolicyType(String policyType) {
        this.policyType = policyType;
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getBehavior() {
        return behavior;
    }

    public void setBehavior(String behavior) {
        this.behavior = behavior;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public Map<String, String> getAttrs() {
        return attrs;
    }

    public void setAttrs(Map<String, String> attrs) {
        this.attrs = attrs;
    }
}
