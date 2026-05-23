package com.travel.ai.runtime.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable input metadata for one workflow turn.
 */
public record WorkflowTask(
        String workflowId,
        String workflowVersion,
        String workflowFamily,
        String requestId,
        String conversationId,
        String userInput,
        Map<String, String> attrs
) {

    public WorkflowTask {
        attrs = copy(attrs);
    }

    public static WorkflowTask of(
            String workflowId,
            String workflowVersion,
            String workflowFamily,
            String requestId,
            String conversationId,
            String userInput
    ) {
        return new WorkflowTask(
                workflowId,
                workflowVersion,
                workflowFamily,
                requestId,
                conversationId,
                userInput,
                Map.of()
        );
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
