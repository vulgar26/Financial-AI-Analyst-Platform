package com.travel.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 主线 SSE 与评测共用编排/超时配置（{@code app.agent.*}）。
 */
@ConfigurationProperties(prefix = "app.agent")
public class AppAgentProperties {

    private Duration totalTimeout = Duration.ofSeconds(120);
    private int maxSteps = 8;
    private Duration toolTimeout = Duration.ofSeconds(3);
    private Duration llmStreamTimeout = Duration.ofSeconds(20);
    private PlanStage planStage = new PlanStage();
    private WorkflowRuntime workflowRuntime = new WorkflowRuntime();

    public Duration getTotalTimeout() {
        return totalTimeout != null ? totalTimeout : Duration.ofSeconds(120);
    }

    public void setTotalTimeout(Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
    }

    public int getMaxSteps() {
        return maxSteps > 0 ? maxSteps : 8;
    }

    public void setMaxSteps(int maxSteps) {
        this.maxSteps = maxSteps;
    }

    public Duration getToolTimeout() {
        return toolTimeout != null ? toolTimeout : Duration.ofSeconds(3);
    }

    public void setToolTimeout(Duration toolTimeout) {
        this.toolTimeout = toolTimeout;
    }

    public Duration getLlmStreamTimeout() {
        return llmStreamTimeout != null ? llmStreamTimeout : Duration.ofSeconds(20);
    }

    public void setLlmStreamTimeout(Duration llmStreamTimeout) {
        this.llmStreamTimeout = llmStreamTimeout;
    }

    public PlanStage getPlanStage() {
        if (planStage == null) {
            planStage = new PlanStage();
        }
        return planStage;
    }

    public void setPlanStage(PlanStage planStage) {
        this.planStage = planStage != null ? planStage : new PlanStage();
    }

    public WorkflowRuntime getWorkflowRuntime() {
        if (workflowRuntime == null) {
            workflowRuntime = new WorkflowRuntime();
        }
        return workflowRuntime;
    }

    public void setWorkflowRuntime(WorkflowRuntime workflowRuntime) {
        this.workflowRuntime = workflowRuntime != null ? workflowRuntime : new WorkflowRuntime();
    }

    /** 嵌套绑定 {@code app.agent.plan-stage.enabled} */
    public static class PlanStage {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Feature flag for the Phase R2 LinearWorkflowRuntime wrapper. Default is off. */
    public static class WorkflowRuntime {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
