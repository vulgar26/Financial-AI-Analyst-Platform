package com.travel.ai.runtime.trace;

import com.travel.ai.runtime.model.NodeStatus;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime trace for one executed workflow stage.
 */
public record StageTrace(
        String stage,
        NodeStatus status,
        Long elapsedMs,
        String errorCode,
        String message,
        Map<String, String> attrs
) {

    public StageTrace {
        attrs = copy(attrs);
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
