package com.travel.ai.agent.workflow;

import com.travel.ai.agent.state.WorkflowTurnState;
import com.travel.ai.runtime.node.PlanStageNode;

/**
 * Narrow callback boundary used by {@link MainChatWorkflowAdapter} in M2.
 *
 * <p>The delegate keeps concrete PLAN/RETRIEVE/TOOL/GUARD business adapters in
 * TravelAgent for now, while moving pre-WRITE workflow runner ownership out of
 * the SSE-facing agent class.</p>
 */
public interface MainChatWorkflowDelegate {

    PlanStageNode.PhysicalStageFlags stagePlanAndResolvePhysicalStages(WorkflowTurnState ctx);

    void onPhysicalStageFlagsResolved(WorkflowTurnState ctx, PlanStageNode.PhysicalStageFlags flags);

    void stageRetrieve(WorkflowTurnState ctx);

    void onRetrieveSkippedByPlan(WorkflowTurnState ctx);

    void stageTool(WorkflowTurnState ctx);

    void onToolSkippedByPlan(WorkflowTurnState ctx);

    void stageGuard(WorkflowTurnState ctx);

    void onGuardSkippedByPlan(WorkflowTurnState ctx);
}
