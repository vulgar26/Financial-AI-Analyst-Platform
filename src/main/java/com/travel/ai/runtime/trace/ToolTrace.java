package com.travel.ai.runtime.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime trace for one tool invocation.
 */
public record ToolTrace(
        String toolName,
        String connector,
        Boolean required,
        Boolean used,
        Boolean succeeded,
        String outcome,
        Long latencyMs,
        String errorCode,
        String inputRef,
        String outputRef,
        Map<String, String> attrs
) {

    public ToolTrace {
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
