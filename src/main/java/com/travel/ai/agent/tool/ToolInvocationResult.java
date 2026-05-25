package com.travel.ai.agent.tool;

import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.tools.ToolResult;

import java.util.List;

/**
 * Result of tool invocation. It is pure data and does not mutate agent turn state.
 */
public record ToolInvocationResult(
        ToolResult toolResult,
        String toolPreface,
        List<PolicyEvent> policyEvents,
        boolean toolSelected,
        String selectedToolName
) {
    public ToolInvocationResult {
        toolPreface = toolPreface != null ? toolPreface : "";
        policyEvents = policyEvents == null ? List.of() : List.copyOf(policyEvents);
    }

    public static ToolInvocationResult noTool() {
        return new ToolInvocationResult(null, "", List.of(), false, null);
    }
}
