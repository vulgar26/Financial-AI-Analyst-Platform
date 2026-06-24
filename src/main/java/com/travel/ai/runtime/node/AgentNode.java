package com.travel.ai.runtime.node;

import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.trace.StageTrace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * 复合节点：外层 runtime 眼里是「一个普通节点」，内部却跑着自己的子图（hierarchical graph）。
 *
 * <p>这是「真自治子图」的骨架：外层 {@link LinearWorkflowRuntime} 编排若干 AgentNode，
 * 每个 AgentNode 内部再用<strong>自己的</strong> {@link LinearWorkflowRuntime} 跑子节点列表，
 * 子图里照样有回边/上界/兜底。外层完全看不见子图内部跑了几趟，只拿到最终产物——这就是「自治」。</p>
 *
 * <p><strong>隔离从何而来</strong>：{@link LinearWorkflowRuntime#run} 内部会 new 一个全新的
 * {@link WorkflowContext} 再返回。子节点读写的全是这个隔离子 context，碰不到外层 context。
 * 两层之间唯一的桥是 {@link #lift}：它只把子图产出的结构化产物（如 EvidencePackage）从子 context
 * 提升到外层 context 的产物槽。子 context 的内部工作内存（replan 计数、改写信号等）不外泄。</p>
 *
 * <p><strong>可观测（阶段 5）</strong>：产物隔离归隔离，但 trace 是<em>独立</em>的可观测通道——出了问题得能
 * 归因到「哪个 Agent 的哪一步」。故子图跑完后，把子 trace 逐条复制到外层并打 {@code agent=<NAME>} +
 * {@code scope=sub} 标签（细粒度，可下钻）；外层引擎随后还会按 {@link #execute} 的返回值自动记一条
 * {@code scope=agent} 的汇总 trace。两层并存：报告既能按 agent 分组，又能从汇总下钻到子步骤。
 * 注意这不破坏隔离——trace 是只读的事后账本，不是子 context 的可写工作内存。</p>
 */
public class AgentNode implements WorkflowNode {

    /** 子图某个节点失败时，AgentNode 整体对外报此 error_code。 */
    public static final String ERROR_CODE_SUBGRAPH_FAILED = "AGENT_SUBGRAPH_FAILED";

    /** trace attrs 归因键：值为 agent 名（如 KNOWLEDGE/ANALYST），标明这条 trace 属于哪个 Agent。 */
    public static final String ATTR_AGENT = "agent";
    /** trace attrs 粒度键：{@code sub}=子图内某一步，{@code agent}=该 Agent 的整体汇总。 */
    public static final String ATTR_SCOPE = "scope";
    public static final String SCOPE_SUB = "sub";
    public static final String SCOPE_AGENT = "agent";

    private final String name;
    private final List<WorkflowNode> subNodes;
    private final LinearWorkflowRuntime subRuntime;
    private final BiConsumer<WorkflowContext, WorkflowContext> lift;

    /**
     * @param name       外层图里的节点名（如 {@code KNOWLEDGE}）
     * @param subNodes   子图节点列表，按声明顺序跑，可含回边
     * @param subRuntime 子图引擎（独立实例，自带 maxRedirects 兜底）
     * @param lift       (子 context, 外层 context) -> 把产物从子 context 提升到外层
     */
    public AgentNode(String name,
                     List<WorkflowNode> subNodes,
                     LinearWorkflowRuntime subRuntime,
                     BiConsumer<WorkflowContext, WorkflowContext> lift) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.subNodes = List.copyOf(Objects.requireNonNull(subNodes, "subNodes must not be null"));
        this.subRuntime = Objects.requireNonNull(subRuntime, "subRuntime must not be null");
        this.lift = Objects.requireNonNull(lift, "lift must not be null");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public NodeResult execute(WorkflowContext outerCtx) {
        // 子图跑在 run() 内部 new 的全新 context 上 —— 隔离免费获得。
        WorkflowContext subCtx = subRuntime.run(outerCtx.getTask(), subNodes);
        // 桥：只搬产物，不搬子 context 的内部工作内存。
        lift.accept(subCtx, outerCtx);
        // 可观测：子 trace 逐条复制到外层并打 agent/scope 标签（细粒度，可下钻）。
        // 引擎随后会按本方法返回值记一条 scope=agent 的汇总 trace，两层并存。
        liftSubTraces(subCtx, outerCtx);
        // 故障传播：子图任一节点失败/超时，AgentNode 整体失败，让外层按既有失败语义处理。
        StageTrace failed = firstFailedTrace(subCtx.getStageTraces());
        if (failed != null) {
            return new NodeResult(
                    NodeStatus.FAILED,
                    null,
                    ERROR_CODE_SUBGRAPH_FAILED,
                    name + " sub-graph failed at " + failed.stage()
                            + (failed.message() != null && !failed.message().isBlank() ? " - " + failed.message() : ""),
                    agentScopeAttrs(),
                    false
            );
        }
        return NodeResult.success(agentScopeAttrs());
    }

    /** 把子图每条 trace 复制到外层，叠加 agent/scope=sub 标签，保留原 attrs（replan 原因、回边信号等）。 */
    private void liftSubTraces(WorkflowContext subCtx, WorkflowContext outerCtx) {
        for (StageTrace sub : subCtx.getStageTraces()) {
            if (sub == null) {
                continue;
            }
            Map<String, String> attrs = new LinkedHashMap<>(sub.attrs() != null ? sub.attrs() : Map.of());
            attrs.put(ATTR_AGENT, name);
            attrs.put(ATTR_SCOPE, SCOPE_SUB);
            outerCtx.addStageTrace(new StageTrace(
                    sub.stage(),
                    sub.status(),
                    sub.elapsedMs(),
                    sub.errorCode(),
                    sub.message(),
                    attrs
            ));
        }
    }

    /** 汇总 trace（引擎据返回值记录）的归因标签：agent=本节点名、scope=agent。 */
    private Map<String, String> agentScopeAttrs() {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put(ATTR_AGENT, name);
        attrs.put(ATTR_SCOPE, SCOPE_AGENT);
        return attrs;
    }

    private static StageTrace firstFailedTrace(List<StageTrace> traces) {
        if (traces == null) {
            return null;
        }
        for (StageTrace trace : traces) {
            if (trace != null && (trace.status() == NodeStatus.FAILED || trace.status() == NodeStatus.TIMEOUT)) {
                return trace;
            }
        }
        return null;
    }
}
