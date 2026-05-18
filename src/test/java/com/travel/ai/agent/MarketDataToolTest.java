package com.travel.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataToolTest {

    private final MarketDataTool tool = new MarketDataTool();

    @Test
    void shouldHandle_marketDataExplainKeywords() {
        assertTrue(tool.shouldHandle("解释一下 AAPL 的 P/E"));
        assertTrue(tool.shouldHandle("帮我看一下 TSLA 的 pe"));
        assertTrue(tool.shouldHandle("分析 NVDA 成交量"));
        assertTrue(tool.shouldHandle("解释指数涨跌"));
        assertTrue(tool.shouldHandle("这个公司的估值怎么看"));
        assertTrue(tool.shouldHandle("Explain AAPL volume"));
        assertFalse(tool.shouldHandle("open a research note"));
    }

    @Test
    void resolveInput_extractsTicker() {
        assertEquals("AAPL", tool.resolveInput("解释一下 AAPL 的 P/E 和成交量"));
        assertEquals("TSLA", tool.resolveInput("what is TSLA price"));
        assertEquals("BRK.B", tool.resolveInput("quote for BRK.B"));
        assertEquals("MOCK", tool.resolveInput("解释一下市场数据"));
    }

    @Test
    void observe_containsMockTimestampFreshnessTradableFalse() {
        String output = tool.observe("AAPL");

        assertTrue(output.contains("mockMode=true"));
        assertTrue(output.contains("mock_market_data=true"));
        assertTrue(output.contains("data_source=local_mock"));
        assertTrue(output.contains("symbol=AAPL"));
        assertTrue(output.contains("freshness=mock_non_realtime"));
        assertTrue(output.contains("tradable=false"));
        assertTrue(output.contains("real_financial_api_used=false"));
        assertTrue(output.contains("trading_capability_used=false"));
        assertTrue(output.contains("not_for_trading=true"));

        String timestamp = valueFor(output, "timestamp");
        String asOf = valueFor(output, "as_of");
        assertFalse(timestamp.isBlank());
        assertEquals(timestamp, asOf);
    }

    @Test
    void observe_doesNotClaimRealtimeOrTrading() {
        String output = tool.observe("AAPL").toLowerCase();

        assertTrue(output.contains("not real-time data"));
        assertTrue(output.contains("must not be used for trading"));
        assertTrue(output.contains("tradable=false"));
        assertTrue(output.contains("real_financial_api_used=false"));
        assertTrue(output.contains("trading_capability_used=false"));
    }

    private static String valueFor(String output, String key) {
        Pattern pattern = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "=(\\S+)\\s*$");
        Matcher matcher = pattern.matcher(output);
        assertTrue(matcher.find(), "missing key: " + key);
        return matcher.group(1);
    }
}
