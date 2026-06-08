package com.travel.ai.finance.fundamentals;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FundamentalsTextRendererTest {

    @Test
    void render_availableSnapshot_containsKeyMetricsAndSource() {
        FundamentalsSnapshot s = new FundamentalsSnapshot(
                "AAPL", "Apple Inc.", "NASDAQ", "Consumer Electronics", "Technology",
                new BigDecimal("307.34"), new BigDecimal("4514011993040"),
                new BigDecimal("36.885107649357536"), new BigDecimal("42.45609553971697"),
                new BigDecimal("9.999096213998698"), new BigDecimal("1.2751142052976814"),
                new BigDecimal("0.2715188219084622"), new BigDecimal("0.47862405358827936"),
                new BigDecimal("0.7954756740006198"), new BigDecimal("1.07035746912159"),
                DataSourceMeta.ok("fmp", Instant.parse("2026-06-08T00:00:00Z"), "USD")
        );

        String out = FundamentalsTextRenderer.render(s);

        assertThat(out).contains("fundamentals_available=true");
        assertThat(out).contains("symbol=AAPL");
        assertThat(out).contains("company_name=Apple Inc.");
        assertThat(out).contains("pe_ratio=36.89");           // 两位小数
        assertThat(out).contains("pb_ratio=42.46");
        assertThat(out).contains("net_profit_margin=27.15%");  // 小数转百分比
        assertThat(out).contains("debt_to_equity=0.80");
        assertThat(out).contains("data_source=fmp");
        assertThat(out).contains("currency=USD");
        assertThat(out).contains("mock_mode=false");
        assertThat(out).contains("不构成投资建议");
    }

    @Test
    void render_nullMetrics_showsNoDataNotZero() {
        // 未盈利公司：PE 缺失（null），不能显示 0 或崩溃。
        FundamentalsSnapshot s = new FundamentalsSnapshot(
                "XYZ", "XYZ Corp", "NYSE", "Biotech", "Healthcare",
                new BigDecimal("12.00"), new BigDecimal("500000000"),
                null, null, null, null,   // PE/PB/PS/PEG 全缺
                null, null,
                null, null,
                DataSourceMeta.ok("fmp", Instant.now(), "USD")
        );

        String out = FundamentalsTextRenderer.render(s);

        assertThat(out).contains("fundamentals_available=true");
        assertThat(out).contains("pe_ratio=暂无数据");
        assertThat(out).contains("net_profit_margin=暂无数据");
        assertThat(out).doesNotContain("pe_ratio=0");
    }

    @Test
    void render_unavailable_showsDegradeNoteNotFabricated() {
        FundamentalsSnapshot s = FundamentalsSnapshot.unavailable("BADSYM",
                DataSourceMeta.unavailable("fmp", Instant.now(), "profile_not_found_or_unavailable"));

        String out = FundamentalsTextRenderer.render(s);

        assertThat(out).contains("fundamentals_available=false");
        assertThat(out).contains("symbol=BADSYM");
        assertThat(out).contains("reason=profile_not_found_or_unavailable");
        assertThat(out).contains("不构成投资建议");
    }

    @Test
    void render_mockSnapshot_marksNonRealtimeNotTradable() {
        FundamentalsSnapshot s = new MockFundamentalsDataSource().fetch("AAPL");

        String out = FundamentalsTextRenderer.render(s);

        assertThat(out).contains("mock_mode=true");
        assertThat(out).contains("freshness=mock_non_realtime");
        assertThat(out).contains("tradable=false");
    }
}
