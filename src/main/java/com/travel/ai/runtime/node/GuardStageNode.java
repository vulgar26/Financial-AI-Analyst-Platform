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
        boolean redirectRequested = delegate != null && delegate.stageGuard();
        if (redirectRequested) {
            return NodeResult.redirectTo(StageName.RETRIEVE.name(),
                    Map.of("replan", "true", "replan_reason", "RAG_EMPTY"));
        }
        return NodeResult.success();
    }

    private static boolean runFlag(WorkflowContext ctx, String key) {
        return ctx != null && Boolean.parseBoolean(ctx.getAttrs().getOrDefault(key, "false"));
    }

    public interface GuardStageDelegate {
        /**
         * 执行 GUARD 业务判断。
         *
         * @return {@code true} 表示检测到「假零命中」、建议跳回 RETRIEVE 重查（replan）；
         *         {@code false} 表示正常前进。老的 inline 路径直接忽略返回值即天然无 replan。
         */
        boolean stageGuard();

        void onGuardSkippedByPlan();
    }
}
