package com.travel.ai.runtime.node;

import com.travel.ai.runtime.model.NodeResult;
import com.travel.ai.runtime.model.WorkflowContext;

public interface WorkflowNode {

    String name();

    NodeResult execute(WorkflowContext ctx);
}
