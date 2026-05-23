package com.travel.ai.runtime.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Structured result returned by a workflow node.
 */
public record NodeResult(
        NodeStatus status,
        Long elapsedMs,
        String errorCode,
        String message,
        Map<String, String> attrs,
        boolean continueWorkflow
) {

    public static final String ERROR_CODE_NODE_EXCEPTION = "NODE_EXCEPTION";
    public static final String ERROR_CODE_NULL_NODE_RESULT = "NULL_NODE_RESULT";

    public NodeResult {
        status = status != null ? status : NodeStatus.SUCCESS;
        attrs = copy(attrs);
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

    public NodeResult withElapsedMs(long elapsedMs) {
        return new NodeResult(status, elapsedMs, errorCode, message, attrs, continueWorkflow);
    }

    public NodeResult stopWorkflow() {
        return new NodeResult(status, elapsedMs, errorCode, message, attrs, false);
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
