package com.travel.ai.runtime.node;

import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

import java.util.Map;

/**
 * Thin runtime node for the RETRIEVE stage.
 *
 * <p>The node owns workflow scheduling semantics only. Query rewrite, vector
 * search, user filtering, dedupe, prompt base, and citation block construction
 * stay in the current agent implementation and are invoked through
 * {@link RetrieveStageDelegate}.
 */
public final class RetrieveStageNode implements WorkflowNode {

    private static final String RUN_RETRIEVE = "run_retrieve";
    private static final String SKIPPED_BY_PLAN = "skipped_by_plan";

    private final RetrieveStageDelegate delegate;

    public RetrieveStageNode(RetrieveStageDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return StageName.RETRIEVE.name();
    }

    @Override
    public NodeResult execute(WorkflowContext ctx) {
        if (!runFlag(ctx, RUN_RETRIEVE)) {
            if (delegate != null) {
                delegate.onRetrieveSkippedByPlan();
            }
            return NodeResult.skipped(SKIPPED_BY_PLAN, Map.of("reason", SKIPPED_BY_PLAN));
        }
        if (delegate != null) {
            delegate.stageRetrieve();
        }
        return NodeResult.success();
    }

    private static boolean runFlag(WorkflowContext ctx, String key) {
        return ctx != null && Boolean.parseBoolean(ctx.getAttrs().getOrDefault(key, "false"));
    }

    public interface RetrieveStageDelegate {
        void stageRetrieve();

        void onRetrieveSkippedByPlan();
    }
}
