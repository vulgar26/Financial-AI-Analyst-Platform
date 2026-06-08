package com.travel.ai.agent;

import com.travel.ai.finance.fundamentals.MockFundamentalsDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketDataToolTest {

    private final MarketDataTool tool = new MarketDataTool(new MockFundamentalsDataSource());

    @Test
    void shouldHandle_marketDataExplainKeywords() {
        assertTrue(tool.shouldHandle("解释一下 AAPL 的 P/E"));
        assertTrue(tool.shouldHandle("帮我看一下 TSLA 的 pe"));
        assertTrue(tool.shouldHandle("分析 NVDA 成交量"));
        assertTrue(tool.shouldHandle("解释指数涨跌"));
        assertTrue(tool.shouldHandle("这个公司的估值怎么看"));
        assertTrue(tool.shouldHandle("看看苹果的基本面"));
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
    void observe_withMockDataSource_marksMockNonRealtime() {
        String output = tool.observe("AAPL");

        // 渲染来自结构化快照（MockFundamentalsDataSource），含合规标注。
        assertTrue(output.contains("symbol=AAPL"));
        assertTrue(output.contains("fundamentals_available=true"));
        assertTrue(output.contains("mock_mode=true"));
        assertTrue(output.contains("freshness=mock_non_realtime"));
        assertTrue(output.contains("tradable=false"));
        assertTrue(output.contains("pe_ratio="));
    }

    @Test
    void observe_doesNotClaimRealtimeOrTrading() {
        String output = tool.observe("AAPL");

        assertTrue(output.contains("mock_mode=true"));
        assertTrue(output.contains("tradable=false"));
        assertTrue(output.contains("非实时") || output.toLowerCase().contains("non_realtime"));
    }
}
