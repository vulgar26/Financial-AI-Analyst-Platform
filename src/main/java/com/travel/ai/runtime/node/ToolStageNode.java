package com.travel.ai.runtime.node;

import com.travel.ai.runtime.StageName;
import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

import java.util.Map;

/**
 * Thin runtime node for the TOOL stage.
 *
 * <p>The node owns workflow scheduling semantics only. Tool selection, execution,
 * policy events, and runtime tool trace capture stay in the current agent
 * implementation and are invoked through {@link ToolStageDelegate}.
 */
public final class ToolStageNode implements WorkflowNode {

    private static final String RUN_TOOL = "run_tool";
    private static final String SKIPPED_BY_PLAN = "skipped_by_plan";

    private final ToolStageDelegate delegate;

    public ToolStageNode(ToolStageDelegate delegate) {
        this.delegate = delegate;
    }

    @Override
    public String name() {
        return StageName.TOOL.name();
    }

    @Override
    public NodeResult execute(WorkflowContext ctx) {
        if (!runFlag(ctx, RUN_TOOL)) {
            if (delegate != null) {
                delegate.onToolSkippedByPlan();
            }
            return NodeResult.skipped(SKIPPED_BY_PLAN, Map.of("reason", SKIPPED_BY_PLAN));
        }
        if (delegate != null) {
            delegate.stageTool();
        }
        return NodeResult.success();
    }

    private static boolean runFlag(WorkflowContext ctx, String key) {
        return ctx != null && Boolean.parseBoolean(ctx.getAttrs().getOrDefault(key, "false"));
    }

    public interface ToolStageDelegate {
        void stageTool();

        void onToolSkippedByPlan();
    }
}
