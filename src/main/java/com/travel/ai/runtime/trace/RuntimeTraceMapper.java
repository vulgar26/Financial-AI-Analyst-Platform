package com.travel.ai.runtime.trace;

import com.travel.ai.tools.ToolOutcome;
import com.travel.ai.tools.ToolResult;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Runtime-only trace mapper.
 *
 * <p>This class intentionally does not depend on eval DTOs.</p>
 */
public final class RuntimeTraceMapper {

    private static final String MARKET_DATA = "market_data";

    private RuntimeTraceMapper() {
    }

    public static ToolTrace toToolTrace(ToolResult result) {
        if (result == null) {
            return null;
        }
        String toolName = result.name();
        return new ToolTrace(
                toolName,
                toolName,
                result.required(),
                result.used(),
                result.succeeded(),
                outcome(result.outcome()),
                result.latencyMs(),
                result.errorCode(),
                null,
                null,
                attrs(result)
        );
    }

    private static String outcome(ToolOutcome outcome) {
        return outcome != null ? outcome.name().toLowerCase(Locale.ROOT) : null;
    }

    private static Map<String, String> attrs(ToolResult result) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        if (result.observationTruncated()) {
            attrs.put("output_truncated", "true");
        }
        if (result.disabledByCircuitBreaker()) {
            attrs.put("disabled_by_circuit_breaker", "true");
        }
        if (result.rateLimited()) {
            attrs.put("rate_limited", "true");
        }
        if (MARKET_DATA.equals(result.name())) {
            attrs.put("mock_mode", "true");
            attrs.put("freshness", "mock_non_realtime");
            attrs.put("tradable", "false");
        }
        return attrs.isEmpty() ? Map.of() : attrs;
    }
}
