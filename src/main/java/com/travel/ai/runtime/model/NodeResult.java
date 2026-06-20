package com.travel.ai.runtime.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured result returned by a workflow node.
 *
 * <p>{@code redirectTarget} carries the back-edge signal that turns this runtime from a straight
 * line into a graph: when non-null, the engine jumps back to the node with that name instead of
 * advancing. null means "advance normally". Redirect implies {@code continueWorkflow=true} — the
 * workflow is not stopping, it is looping back.</p>
 */
public record NodeResult(
        NodeStatus status,
        Long elapsedMs,
        String errorCode,
        String message,
        Map<String, String> attrs,
        boolean continueWorkflow,
        String redirectTarget
) {

    public static final String ERROR_CODE_NODE_EXCEPTION = "NODE_EXCEPTION";
    public static final String ERROR_CODE_NULL_NODE_RESULT = "NULL_NODE_RESULT";

    public NodeResult {
        status = status != null ? status : NodeStatus.SUCCESS;
        attrs = copy(attrs);
    }

    /** Backward-compatible constructor for nodes that never redirect (no back-edge). */
    public NodeResult(NodeStatus status, Long elapsedMs, String errorCode, String message,
                      Map<String, String> attrs, boolean continueWorkflow) {
        this(status, elapsedMs, errorCode, message, attrs, continueWorkflow, null);
    }

    public static NodeResult success() {
        return new NodeResult(NodeStatus.SUCCESS, null, null, null, Map.of(), true);
    }

    public static NodeResult success(Map<String, String> attrs) {
        return new NodeResult(NodeStatus.SUCCESS, null, null, null, attrs, true);
    }

    public static NodeResult skipped(String message, Map<String, String> attrs) {
        return new NodeResult(NodeStatus.SKIPPED, null, null, message, attrs, true);
    }

    public static NodeResult failed(String errorCode, String message) {
        return new NodeResult(NodeStatus.FAILED, null, errorCode, message, Map.of(), false);
    }

    public static NodeResult timeout(String errorCode, String message) {
        return new NodeResult(NodeStatus.TIMEOUT, null, errorCode, message, Map.of(), false);
    }

    /**
     * Signal the engine to jump back to {@code targetNodeName} for another pass (a back-edge).
     * Used by the GUARD node to send the workflow back to RETRIEVE for a replan attempt.
     */
    public static NodeResult redirectTo(String targetNodeName, Map<String, String> attrs) {
        return new NodeResult(NodeStatus.SUCCESS, null, null, null, attrs, true, targetNodeName);
    }

    public NodeResult withElapsedMs(long elapsedMs) {
        return new NodeResult(status, elapsedMs, errorCode, message, attrs, continueWorkflow, redirectTarget);
    }

    public NodeResult stopWorkflow() {
        return new NodeResult(status, elapsedMs, errorCode, message, attrs, false, redirectTarget);
    }

    private static Map<String, String> copy(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        source.forEach((k, v) -> {
            if (k != null && !k.isBlank() && v != null) {
                out.put(k, v);
            }
        });
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }
}
