package com.travel.ai.runtime.node;

import com.travel.ai.runtime.LinearWorkflowRuntime;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.trace.StageTrace;

import java.util.List;
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
 */
public class AgentNode implements WorkflowNode {

    /** 子图某个节点失败时，AgentNode 整体对外报此 error_code。 */
    public static final String ERROR_CODE_SUBGRAPH_FAILED = "AGENT_SUBGRAPH_FAILED";

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
        // 故障传播：子图任一节点失败/超时，AgentNode 整体失败，让外层按既有失败语义处理。
        StageTrace failed = firstFailedTrace(subCtx.getStageTraces());
        if (failed != null) {
            return NodeResult.failed(ERROR_CODE_SUBGRAPH_FAILED,
                    name + " sub-graph failed at " + failed.stage()
                            + (failed.message() != null && !failed.message().isBlank() ? " - " + failed.message() : ""));
        }
        return NodeResult.success();
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
