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
 * Minimal workflow runtime.
 *
 * <p>Nodes run in declared order. A node may return a {@code redirectTarget} (a back-edge) to send
 * execution back to an earlier node for another pass — this is what turns the straight line into a
 * graph and enables Plan-Execute-Replan. Back-edges are bounded by {@link #maxRedirects} to
 * guarantee termination (no infinite loops).</p>
 *
 * <p>This runtime is intentionally not wired into FinancialAnalystAgentImpl directly; it is driven
 * through MainChatWorkflowAdapter behind a feature flag.</p>
 */
public class LinearWorkflowRuntime {

    /** Default upper bound on back-edge jumps across one workflow run (defensive anti-loop cap). */
    public static final int DEFAULT_MAX_REDIRECTS = 3;

    private final int maxRedirects;

    public LinearWorkflowRuntime() {
        this(DEFAULT_MAX_REDIRECTS);
    }

    public LinearWorkflowRuntime(int maxRedirects) {
        this.maxRedirects = Math.max(0, maxRedirects);
    }

    public WorkflowContext run(WorkflowTask task, List<WorkflowNode> nodes) {
        WorkflowContext ctx = new WorkflowContext(task);
        if (nodes == null || nodes.isEmpty()) {
            return ctx;
        }
        int redirectsUsed = 0;
        int i = 0;
        while (i < nodes.size()) {
            WorkflowNode node = nodes.get(i);
            Objects.requireNonNull(node, "workflow node must not be null");
            NodeResult result = executeNode(ctx, node);
            ctx.addStageTrace(toStageTrace(node, result));
            if (!result.continueWorkflow()) {
                break;
            }
            String redirectTarget = result.redirectTarget();
            if (redirectTarget != null && !redirectTarget.isBlank()) {
                if (redirectsUsed >= maxRedirects) {
                    // Anti-loop cap hit: ignore the back-edge and advance so the workflow terminates.
                    ctx.putAttr("redirect_capped", "true");
                    i++;
                    continue;
                }
                int targetIndex = indexOfNode(nodes, redirectTarget);
                if (targetIndex < 0) {
                    // Unknown target: cannot honor the back-edge; advance rather than loop forever.
                    ctx.putAttr("redirect_unresolved", redirectTarget);
                    i++;
                    continue;
                }
                redirectsUsed++;
                i = targetIndex;
                continue;
            }
            i++;
        }
        return ctx;
    }

    private static int indexOfNode(List<WorkflowNode> nodes, String name) {
        for (int j = 0; j < nodes.size(); j++) {
            WorkflowNode n = nodes.get(j);
            if (n != null && name.equals(n.name())) {
                return j;
            }
        }
        return -1;
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
