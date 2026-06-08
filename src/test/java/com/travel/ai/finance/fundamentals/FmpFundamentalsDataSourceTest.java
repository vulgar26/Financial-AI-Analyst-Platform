package com.travel.ai.finance.fundamentals;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import org.springframework.http.MediaType;

/**
 * FMP 数据源解析测试：用真实端点返回的 JSON 片段（来自 FMP playground）做样本，
 * 通过 MockRestServiceServer stub HTTP，不打真实网络、不烧额度。
 */
class FmpFundamentalsDataSourceTest {

    private static final String BASE = "https://financialmodelingprep.com/stable";

    // 真实 /stable/profile?symbol=AAPL 返回片段（节选关键字段）
    private static final String PROFILE_JSON = """
            [{
              "symbol": "AAPL",
              "price": 307.34,
              "marketCap": 4514011993040,
              "companyName": "Apple Inc.",
              "currency": "USD",
              "exchange": "NASDAQ",
              "industry": "Consumer Electronics",
              "sector": "Technology"
            }]
            """;

    // 真实 /stable/ratios-ttm?symbol=AAPL 返回片段（节选关键字段）
    private static final String RATIOS_JSON = """
            [{
              "symbol": "AAPL",
              "netProfitMarginTTM": 0.2715188219084622,
              "grossProfitMarginTTM": 0.47862405358827936,
              "priceToEarningsRatioTTM": 36.885107649357536,
              "priceToEarningsGrowthRatioTTM": 1.2751142052976814,
              "priceToBookRatioTTM": 42.45609553971697,
              "priceToSalesRatioTTM": 9.999096213998698,
              "debtToEquityRatioTTM": 0.7954756740006198,
              "currentRatioTTM": 1.07035746912159
            }]
            """;

    private FmpFundamentalsDataSource sourceWith(MockRestServiceServer[] serverOut) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        serverOut[0] = server;
        return new FmpFundamentalsDataSource(builder.build(), "test-key");
    }

    @Test
    void fetch_parsesProfileAndRatiosIntoSnapshot() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        FmpFundamentalsDataSource source = sourceWith(holder);
        MockRestServiceServer server = holder[0];

        server.expect(requestTo(BASE + "/profile?symbol=AAPL&apikey=test-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/ratios-ttm?symbol=AAPL&apikey=test-key"))
                .andRespond(withSuccess(RATIOS_JSON, MediaType.APPLICATION_JSON));

        FundamentalsSnapshot s = source.fetch("AAPL");

        assertThat(s.available()).isTrue();
        assertThat(s.symbol()).isEqualTo("AAPL");
        assertThat(s.companyName()).isEqualTo("Apple Inc.");
        assertThat(s.sector()).isEqualTo("Technology");
        assertThat(s.price()).isEqualByComparingTo("307.34");
        assertThat(s.marketCap()).isEqualByComparingTo("4514011993040");
        assertThat(s.peRatio()).isEqualByComparingTo("36.885107649357536");
        assertThat(s.pbRatio()).isEqualByComparingTo("42.45609553971697");
        assertThat(s.netProfitMargin()).isEqualByComparingTo("0.2715188219084622");
        assertThat(s.debtToEquity()).isEqualByComparingTo("0.7954756740006198");
        assertThat(s.meta().source()).isEqualTo("fmp");
        assertThat(s.meta().currency()).isEqualTo("USD");
        assertThat(s.meta().mockMode()).isFalse();
        server.verify();
    }

    @Test
    void fetch_lowercaseSymbol_isUppercasedInRequest() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        FmpFundamentalsDataSource source = sourceWith(holder);
        MockRestServiceServer server = holder[0];
        server.expect(requestTo(BASE + "/profile?symbol=AAPL&apikey=test-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/ratios-ttm?symbol=AAPL&apikey=test-key"))
                .andRespond(withSuccess(RATIOS_JSON, MediaType.APPLICATION_JSON));

        FundamentalsSnapshot s = source.fetch("aapl");

        assertThat(s.symbol()).isEqualTo("AAPL");
        server.verify();
    }

    @Test
    void fetch_symbolNotFound_returnsUnavailableNotException() {
        // FMP 对无效 symbol 返回空数组 []
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        FmpFundamentalsDataSource source = sourceWith(holder);
        MockRestServiceServer server = holder[0];
        server.expect(requestTo(BASE + "/profile?symbol=NOTREAL&apikey=test-key"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        FundamentalsSnapshot s = source.fetch("NOTREAL");

        assertThat(s.available()).isFalse();
        assertThat(s.meta().unavailableReason()).isEqualTo("profile_not_found_or_unavailable");
        assertThat(s.peRatio()).isNull();
    }

    @Test
    void fetch_ratiosMissing_stillReturnsProfileFields() {
        // ratios 端点失败（如限额），profile 仍成功 → 快照可用，比率字段为 null。
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        FmpFundamentalsDataSource source = sourceWith(holder);
        MockRestServiceServer server = holder[0];
        server.expect(requestTo(BASE + "/profile?symbol=AAPL&apikey=test-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/ratios-ttm?symbol=AAPL&apikey=test-key"))
                .andRespond(withServerError());

        FundamentalsSnapshot s = source.fetch("AAPL");

        assertThat(s.available()).isTrue();
        assertThat(s.companyName()).isEqualTo("Apple Inc.");
        assertThat(s.peRatio()).isNull();   // 比率缺失但不致命
    }

    @Test
    void fetch_emptySymbol_returnsUnavailable() {
        MockRestServiceServer[] holder = new MockRestServiceServer[1];
        FmpFundamentalsDataSource source = sourceWith(holder);

        FundamentalsSnapshot s = source.fetch("  ");

        assertThat(s.available()).isFalse();
        assertThat(s.meta().unavailableReason()).isEqualTo("empty_symbol");
    }
}
