package com.travel.ai.runtime.node;

import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

import java.util.Map;

/**
 * Thin runtime node for the GUARD stage.
 *
 * <p>The node owns workflow scheduling semantics only. Guard business logic stays
 * in the current agent implementation and is invoked through {@link GuardStageDelegate}.
 */
public final class GuardStageNode implements WorkflowNode {

    private static final String RUN_GUARD = "run_guard";
    private static final String SKIPPED_BY_PLAN = "skipped_by_plan";

    private final GuardStageDelegate delegate;

    public GuardStageNode(GuardStageDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return StageName.GUARD.name();
    }

    @Override
    public NodeResult execute(WorkflowContext ctx) {
        if (!runFlag(ctx, RUN_GUARD)) {
            if (delegate != null) {
                delegate.onGuardSkippedByPlan();
            }
            return NodeResult.skipped(SKIPPED_BY_PLAN, Map.of("reason", SKIPPED_BY_PLAN));
        }
        if (delegate != null) {
            delegate.stageGuard();
        }
        return NodeResult.success();
    }

    private static boolean runFlag(WorkflowContext ctx, String key) {
        return ctx != null && Boolean.parseBoolean(ctx.getAttrs().getOrDefault(key, "false"));
    }

    public interface GuardStageDelegate {
        void stageGuard();

        void onGuardSkippedByPlan();
    }
}
