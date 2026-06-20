package com.travel.ai.runtime;

import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.NodeStatus;
import com.travel.ai.runtime.model.WorkflowContext;
import com.travel.ai.runtime.model.WorkflowTask;
import com.travel.ai.runtime.node.WorkflowNode;
import com.travel.ai.runtime.trace.StageTrace;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Minimal linear workflow runtime for Phase R1.
 *
 * <p>This runtime is intentionally not wired into FinancialAnalystAgentImpl yet.</p>
 */
public class LinearWorkflowRuntime {

    public WorkflowContext run(WorkflowTask task, List<WorkflowNode> nodes) {
        WorkflowContext ctx = new WorkflowContext(task);
        if (nodes == null || nodes.isEmpty()) {
            return ctx;
        }
        for (WorkflowNode node : nodes) {
            Objects.requireNonNull(node, "workflow node must not be null");
            NodeResult result = executeNode(ctx, node);
            ctx.addStageTrace(toStageTrace(node, result));
            if (!result.continueWorkflow()) {
                break;
            }
        }
        return ctx;
    }

    private NodeResult executeNode(WorkflowContext ctx, WorkflowNode node) {
        long t0 = System.nanoTime();
        try {
            NodeResult result = node.execute(ctx);
            long elapsedMs = elapsedMs(t0);
            if (result == null) {
                return new NodeResult(
                        NodeStatus.FAILED,
                        elapsedMs,
                        NodeResult.ERROR_CODE_NULL_NODE_RESULT,
                        "workflow node returned null",
                        Map.of(),
                        false
                );
            }
            return result.withElapsedMs(elapsedMs);
        } catch (Exception e) {
            return new NodeResult(
                    NodeStatus.FAILED,
                    elapsedMs(t0),
                    NodeResult.ERROR_CODE_NODE_EXCEPTION,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(),
                    Map.of("exception", e.getClass().getName()),
                    false
            );
        }
    }

    private static StageTrace toStageTrace(WorkflowNode node, NodeResult result) {
        String stage = node.name();
        if (stage == null || stage.isBlank()) {
            stage = node.getClass().getSimpleName();
        }
        return new StageTrace(
                stage,
                result.status(),
                result.elapsedMs(),
                result.errorCode(),
                result.message(),
                result.attrs()
        );
    }

    private static long elapsedMs(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }
}
