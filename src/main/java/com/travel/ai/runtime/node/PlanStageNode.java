package com.travel.ai.runtime.node;

import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

/**
 * Thin runtime node for the PLAN stage.
 *
 * <p>The node owns workflow scheduling semantics only. Plan proposal, parsing,
 * repair, fallback, and physical stage policy resolution stay in the current
 * agent implementation and are invoked through {@link PlanStageDelegate}.
 */
public final class PlanStageNode implements WorkflowNode {

    private static final String RUN_RETRIEVE = "run_retrieve";
    private static final String RUN_TOOL = "run_tool";
    private static final String RUN_GUARD = "run_guard";

    private final PlanStageDelegate delegate;

    public PlanStageNode(PlanStageDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return StageName.PLAN.name();
    }

    @Override
    public NodeResult execute(WorkflowContext ctx) {
        PhysicalStageFlags flags = delegate != null
                ? delegate.stagePlanAndResolvePhysicalStages()
                : new PhysicalStageFlags(false, false, false);
        if (ctx != null) {
            ctx.putAttr(RUN_RETRIEVE, String.valueOf(flags.runRetrieve()));
            ctx.putAttr(RUN_TOOL, String.valueOf(flags.runTool()));
            ctx.putAttr(RUN_GUARD, String.valueOf(flags.runGuard()));
        }
        if (delegate != null) {
            delegate.onPhysicalStageFlagsResolved(flags);
        }
        return NodeResult.success();
    }

    public record PhysicalStageFlags(boolean runRetrieve, boolean runTool, boolean runGuard) {
    }

    public interface PlanStageDelegate {
        PhysicalStageFlags stagePlanAndResolvePhysicalStages();

        default void onPhysicalStageFlagsResolved(PhysicalStageFlags flags) {
        }
    }
}
