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
    private MultiAgent multiAgent = new MultiAgent();
    private Rag rag = new Rag();

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

    public MultiAgent getMultiAgent() {
        if (multiAgent == null) {
            multiAgent = new MultiAgent();
        }
        return multiAgent;
    }

    public void setMultiAgent(MultiAgent multiAgent) {
        this.multiAgent = multiAgent != null ? multiAgent : new MultiAgent();
    }

    public Rag getRag() {
        if (rag == null) {
            rag = new Rag();
        }
        return rag;
    }

    public void setRag(Rag rag) {
        this.rag = rag != null ? rag : new Rag();
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

    /**
     * 多 Agent 图编排路径开关（{@code app.agent.multi-agent.enabled}）。默认 off。
     *
     * <p>开时走 KnowledgeAgentNode/AnalystAgentNode 复合节点的外层图编排；否则走现有两条路径
     * （workflow-runtime 线性 / inline 内联）。三条路径优先级：multi-agent > workflow-runtime > inline。</p>
     */
    public static class MultiAgent {
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * RAG 检索调参（{@code app.agent.rag.*}）。
     *
     * <p>这三个值原先散落在代码里硬编码（topK 是构造器字面量、maxContextDocs 是常量、
     * 相似度阈值干脆没设导致默认 0.0「再不相关也算命中」），现统一收编为单一事实源。</p>
     */
    public static class Rag {
        /** 每路改写 query 的向量检索取前 K 条。 */
        private int topKPerQuery = 2;
        /** 多路结果合并去重后进入 prompt 的文档条数上限。 */
        private int maxContextDocs = 5;
        /**
         * 相似度阈值（余弦相似度，0~1）。低于此值的命中视为不相关、直接丢弃。
         * 默认 0.5，偏保守：宁可漏掉边缘片段，也不要把无关内容塞进上下文。
         * Spring AI 的默认值是 0.0（即不过滤），这正是「针对一条问却全库都检索出来」的病根。
         */
        private double similarityThreshold = 0.5;
        /**
         * Replan 重查时的降级相似度阈值。GUARD 检测到「假零命中」跳回 RETRIEVE 时启用，
         * 比常规阈值更宽松，用于判别零命中是「知识真缺失」还是「阈值过严拦掉了相关片段」。
         * 默认 0.35：放宽但不归零——归零等于退回 Spring AI「全库都算命中」的病根。
         */
        private double replanSimilarityThreshold = 0.35;

        public int getTopKPerQuery() {
            return topKPerQuery > 0 ? topKPerQuery : 2;
        }

        public void setTopKPerQuery(int topKPerQuery) {
            this.topKPerQuery = topKPerQuery;
        }

        public int getMaxContextDocs() {
            return maxContextDocs > 0 ? maxContextDocs : 5;
        }

        public void setMaxContextDocs(int maxContextDocs) {
            this.maxContextDocs = maxContextDocs;
        }

        public double getSimilarityThreshold() {
            return similarityThreshold >= 0.0 ? similarityThreshold : 0.5;
        }

        public void setSimilarityThreshold(double similarityThreshold) {
            this.similarityThreshold = similarityThreshold;
        }

        public double getReplanSimilarityThreshold() {
            return replanSimilarityThreshold >= 0.0 ? replanSimilarityThreshold : 0.35;
        }

        public void setReplanSimilarityThreshold(double replanSimilarityThreshold) {
            this.replanSimilarityThreshold = replanSimilarityThreshold;
        }
    }
}
