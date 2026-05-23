package com.travel.ai.runtime.trace;

import com.travel.ai.tools.ToolOutcome;
import com.travel.ai.tools.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeTraceMapperTest {

    @Test
    void toolResultMapper_mapsOkMarketData() {
        ToolResult result = new ToolResult(
                "market_data",
                true,
                true,
                true,
                ToolOutcome.OK,
                null,
                42L,
                "mock quote",
                true
        );

        ToolTrace trace = RuntimeTraceMapper.toToolTrace(result);

        assertThat(trace.toolName()).isEqualTo("market_data");
        assertThat(trace.connector()).isEqualTo("market_data");
        assertThat(trace.required()).isTrue();
        assertThat(trace.used()).isTrue();
        assertThat(trace.succeeded()).isTrue();
        assertThat(trace.outcome()).isEqualTo("ok");
        assertThat(trace.latencyMs()).isEqualTo(42L);
        assertThat(trace.errorCode()).isNull();
        assertThat(trace.attrs()).containsEntry("mock_mode", "true")
                .containsEntry("freshness", "mock_non_realtime")
                .containsEntry("tradable", "false")
                .containsEntry("output_truncated", "true");
    }

    @Test
    void toolResultMapper_mapsTimeout() {
        ToolResult result = new ToolResult(
                "weather",
                true,
                true,
                false,
                ToolOutcome.TIMEOUT,
                "TOOL_TIMEOUT",
                3000L,
                null,
                false
        );

        ToolTrace trace = RuntimeTraceMapper.toToolTrace(result);

        assertThat(trace.toolName()).isEqualTo("weather");
        assertThat(trace.connector()).isEqualTo("weather");
        assertThat(trace.required()).isTrue();
        assertThat(trace.used()).isTrue();
        assertThat(trace.succeeded()).isFalse();
        assertThat(trace.outcome()).isEqualTo("timeout");
        assertThat(trace.latencyMs()).isEqualTo(3000L);
        assertThat(trace.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(trace.attrs()).isEmpty();
    }

    @Test
    void toolResultMapper_mapsDisabledByCircuitBreakerOrPolicy() {
        ToolTrace circuitBreaker = RuntimeTraceMapper.toToolTrace(
                ToolResult.disabledByCircuitBreaker("market_data", true, "TOOL_DISABLED_BY_CIRCUIT_BREAKER")
        );
        ToolTrace policy = RuntimeTraceMapper.toToolTrace(
                ToolResult.disabledByPolicy("weather", true, "TOOL_POLICY_DISABLED")
        );

        assertThat(circuitBreaker.used()).isFalse();
        assertThat(circuitBreaker.succeeded()).isFalse();
        assertThat(circuitBreaker.outcome()).isEqualTo("disabled_by_circuit_breaker");
        assertThat(circuitBreaker.errorCode()).isEqualTo("TOOL_DISABLED_BY_CIRCUIT_BREAKER");
        assertThat(circuitBreaker.attrs()).containsEntry("disabled_by_circuit_breaker", "true")
                .containsEntry("mock_mode", "true");

        assertThat(policy.used()).isFalse();
        assertThat(policy.succeeded()).isFalse();
        assertThat(policy.outcome()).isEqualTo("disabled_by_policy");
        assertThat(policy.errorCode()).isEqualTo("TOOL_POLICY_DISABLED");
        assertThat(policy.attrs()).isEmpty();
    }

    @Test
    void toolResultMapper_mapsRateLimited() {
        ToolTrace trace = RuntimeTraceMapper.toToolTrace(
                ToolResult.rateLimited("market_data", true, "RATE_LIMITED")
        );

        assertThat(trace.used()).isFalse();
        assertThat(trace.succeeded()).isFalse();
        assertThat(trace.outcome()).isEqualTo("rate_limited");
        assertThat(trace.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(trace.attrs()).containsEntry("rate_limited", "true")
                .containsEntry("mock_mode", "true")
                .containsEntry("freshness", "mock_non_realtime")
                .containsEntry("tradable", "false");
    }

    @Test
    void toolResultMapper_doesNotInventInputOutputRef() {
        ToolTrace trace = RuntimeTraceMapper.toToolTrace(
                new ToolResult("weather", true, true, true, ToolOutcome.OK, null, 3L, "sunny", false)
        );

        assertThat(trace.inputRef()).isNull();
        assertThat(trace.outputRef()).isNull();
    }
}
