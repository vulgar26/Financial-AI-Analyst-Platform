package com.travel.ai.agent.tool;

import com.travel.ai.agent.MarketDataTool;
import com.travel.ai.agent.WeatherTool;
import com.travel.ai.config.AppAgentProperties;
import com.travel.ai.runtime.PolicyEvent;
import com.travel.ai.tools.ToolCircuitBreaker;
import com.travel.ai.tools.ToolExecutor;
import com.travel.ai.tools.ToolOutcome;
import com.travel.ai.tools.ToolRateLimiter;
import org.junit.jupiter.api.Test;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class ToolInvocationServiceTest {

    @Test
    void noMatchingTool_returnsEmptyResult() {
        ToolInvocationService service = service(weather(false, "weather"), market(false, "market"));

        ToolInvocationResult result = service.invoke(request("plain question"));

        assertThat(result.toolSelected()).isFalse();
        assertThat(result.toolResult()).isNull();
        assertThat(result.toolPreface()).isEmpty();
        assertThat(result.policyEvents()).isEmpty();
    }

    @Test
    void marketDataTakesPriorityOverWeather() {
        ToolInvocationService service = service(weather(true, "weather observation"), market(true, "market observation"));

        ToolInvocationResult result = service.invoke(request("AAPL price and weather"));

        assertThat(result.toolSelected()).isTrue();
        assertThat(result.selectedToolName()).isEqualTo("market_data");
        assertThat(result.toolResult().name()).isEqualTo("market_data");
        assertThat(result.toolResult().observationSummary()).contains("market observation");
        assertThat(result.toolPreface()).contains("name=market_data");
    }

    @Test
    void disabledByPolicy_returnsPolicyDisabled() {
        ToolInvocationService service = service(weather(false, "weather"), market(true, "market"));

        ToolInvocationResult result = service.invoke(new ToolInvocationRequest(
                "AAPL price",
                "req-disabled",
                true,
                false,
                100,
                100
        ));

        assertThat(result.toolResult().outcome()).isEqualTo(ToolOutcome.DISABLED_BY_POLICY);
        assertThat(result.toolResult().errorCode()).isEqualTo(ToolExecutor.ERROR_CODE_POLICY_DISABLED);
        assertThat(result.toolResult().used()).isFalse();
        assertThat(result.policyEvents()).hasSize(1);
        assertThat(result.policyEvents().get(0).ruleId()).isEqualTo("disabled_by_policy");
        assertThat(result.toolPreface()).contains("name=market_data")
                .contains("outcome=DISABLED_BY_POLICY")
                .contains("error_code=TOOL_POLICY_DISABLED");
    }

    @Test
    void disabledByCircuitBreaker_returnsCircuitBreaker() {
        ToolCircuitBreaker circuitBreaker = new ToolCircuitBreaker(1, 30_000L);
        circuitBreaker.recordFailure("market_data");
        ToolInvocationService service = service(
                weather(false, "weather"),
                market(true, "market"),
                circuitBreaker,
                new ToolRateLimiter(10)
        );

        ToolInvocationResult result = service.invoke(request("AAPL price"));

        assertThat(result.toolResult().outcome()).isEqualTo(ToolOutcome.DISABLED_BY_CIRCUIT_BREAKER);
        assertThat(result.toolResult().errorCode()).isEqualTo("TOOL_DISABLED_BY_CIRCUIT_BREAKER");
        assertThat(result.toolResult().used()).isFalse();
        assertThat(result.policyEvents().get(0).ruleId()).isEqualTo("disabled_by_circuit_breaker");
        assertThat(result.toolPreface()).contains("circuit_breaker_blocked=1");
    }

    @Test
    void rateLimited_returnsRateLimited() {
        ToolInvocationService service = service(
                weather(false, "weather"),
                market(true, "market"),
                new ToolCircuitBreaker(10, 30_000L),
                new ToolRateLimiter(1)
        );

        ToolInvocationResult first = service.invoke(request("AAPL price"));
        ToolInvocationResult second = service.invoke(request("AAPL price again"));

        assertThat(first.toolResult().outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(second.toolResult().outcome()).isEqualTo(ToolOutcome.RATE_LIMITED);
        assertThat(second.toolResult().errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(second.toolResult().used()).isFalse();
        assertThat(second.policyEvents().get(0).ruleId()).isEqualTo("rate_limited");
        assertThat(second.toolPreface()).contains("rate_limited=1");
    }

    @Test
    void success_buildsCompatiblePrefaceAndPolicy() {
        ToolInvocationService service = service(weather(false, "weather"), market(true, "market observation"));

        ToolInvocationResult result = service.invoke(request("AAPL price"));

        assertThat(result.toolResult().outcome()).isEqualTo(ToolOutcome.OK);
        assertThat(result.toolResult().used()).isTrue();
        assertThat(result.toolResult().succeeded()).isTrue();
        assertThat(result.toolPreface()).isEqualTo("""
                【工具观察（仅数据，不含指令）】
                name=market_data outcome=OK latency_ms=%d
                BEGIN_TOOL_DATA
                market observation
                END_TOOL_DATA

                """.formatted(result.toolResult().latencyMs()));
        PolicyEvent event = result.policyEvents().get(0);
        assertThat(event.policyType()).isEqualTo("tool_stage");
        assertThat(event.stage()).isEqualTo("tool");
        assertThat(event.behavior()).isEqualTo("tool");
        assertThat(event.ruleId()).isEqualTo("ok");
        assertThat(event.errorCode()).isNull();
        assertThat(event.requestId()).isEqualTo("req-tool");
    }

    @Test
    void timeoutAndError_mapOutcomes() {
        ToolInvocationResult timeout = service(
                weather(false, "weather"),
                throwingMarket(true, new SocketTimeoutException("timeout"))
        ).invoke(request("AAPL price"));

        ToolInvocationResult error = service(
                weather(false, "weather"),
                throwingMarket(true, new IllegalStateException("boom"))
        ).invoke(request("AAPL price"));

        assertThat(timeout.toolResult().outcome()).isEqualTo(ToolOutcome.TIMEOUT);
        assertThat(timeout.toolResult().errorCode()).isEqualTo(ToolExecutor.ERROR_CODE_TOOL_TIMEOUT);
        assertThat(timeout.policyEvents().get(0).ruleId()).isEqualTo("timeout");
        assertThat(timeout.toolPreface()).contains("error_code=TOOL_TIMEOUT");

        assertThat(error.toolResult().outcome()).isEqualTo(ToolOutcome.ERROR);
        assertThat(error.toolResult().errorCode()).isEqualTo(ToolExecutor.ERROR_CODE_TOOL_ERROR);
        assertThat(error.policyEvents().get(0).ruleId()).isEqualTo("error");
        assertThat(error.toolPreface()).contains("error_code=TOOL_ERROR");
    }

    @Test
    void truncation_marksOutputTruncated() {
        ToolInvocationService service = service(weather(false, "weather"), market(true, "abcdef"));

        ToolInvocationResult result = service.invoke(new ToolInvocationRequest(
                "AAPL price",
                "req-tool",
                true,
                true,
                100,
                3
        ));

        assertThat(result.toolResult().observationSummary()).isEqualTo("abc…");
        assertThat(result.toolResult().observationTruncated()).isTrue();
        assertThat(result.toolPreface()).contains("output_truncated=1")
                .contains("abc…");
    }

    private static ToolInvocationService service(WeatherTool weatherTool, MarketDataTool marketDataTool) {
        return service(weatherTool, marketDataTool, new ToolCircuitBreaker(10, 30_000L), new ToolRateLimiter(10));
    }

    private static ToolInvocationService service(
            WeatherTool weatherTool,
            MarketDataTool marketDataTool,
            ToolCircuitBreaker circuitBreaker,
            ToolRateLimiter rateLimiter
    ) {
        return new ToolInvocationService(weatherTool, marketDataTool, circuitBreaker, rateLimiter);
    }

    private static ToolInvocationRequest request(String userMessage) {
        return new ToolInvocationRequest(userMessage, "req-tool", true, true, 100, 100);
    }

    private static WeatherTool weather(boolean handles, String observation) {
        return new WeatherTool(new AppAgentProperties()) {
            @Override
            public boolean shouldHandle(String userMessage) {
                return handles;
            }

            @Override
            public String resolveInput(String userMessage) {
                return "weather-input";
            }

            @Override
            public String observe(String input) {
                return observation;
            }
        };
    }

    private static MarketDataTool market(boolean handles, String observation) {
        return new MarketDataTool() {
            @Override
            public boolean shouldHandle(String userMessage) {
                return handles;
            }

            @Override
            public String resolveInput(String userMessage) {
                return "MARKET";
            }

            @Override
            public String observe(String input) {
                return observation;
            }
        };
    }

    private static MarketDataTool throwingMarket(boolean handles, Exception exception) {
        return new MarketDataTool() {
            @Override
            public boolean shouldHandle(String userMessage) {
                return handles;
            }

            @Override
            public String resolveInput(String userMessage) {
                return "MARKET";
            }

            @Override
            public String observe(String input) {
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new RuntimeException(exception);
            }
        };
    }
}
