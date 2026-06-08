package com.travel.ai.finance.fundamentals;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Mock 基本面数据源：返回固定占位数据，不调用任何外部 API。
 *
 * <p>两个用途：</p>
 * <ul>
 *   <li><b>降级</b>：未配置 {@code FMP_API_KEY} 时自动启用，使项目无 key 也能跑（开发 / CI 友好）。</li>
 *   <li><b>测试</b>：注入它即可测工具 / 渲染链路，不打真实网络、不烧 API 额度。</li>
 * </ul>
 *
 * <p>所有快照 {@code meta.mockMode=true}，渲染层据此明确标注「非实时 / 不可交易」，
 * 守住「mock 数据必须显式标注」的合规边界。</p>
 */
public class MockFundamentalsDataSource implements FundamentalsDataSource {

    @Override
    public FundamentalsSnapshot fetch(String symbol) {
        String sym = (symbol == null || symbol.isBlank()) ? "MOCK" : symbol.trim().toUpperCase();
        return new FundamentalsSnapshot(
                sym,
                sym + " (Mock Company)",
                "MOCK_EXCHANGE",
                "Mock Industry",
                "Mock Sector",
                new BigDecimal("123.45"),          // price
                new BigDecimal("1000000000"),      // marketCap
                new BigDecimal("20.00"),           // peRatio
                new BigDecimal("3.00"),            // pbRatio
                new BigDecimal("5.00"),            // psRatio
                new BigDecimal("1.50"),            // pegRatio
                new BigDecimal("0.15"),            // netProfitMargin
                new BigDecimal("0.40"),            // grossProfitMargin
                new BigDecimal("0.50"),            // debtToEquity
                new BigDecimal("1.50"),            // currentRatio
                DataSourceMeta.mock(Instant.now())
        );
    }
}
