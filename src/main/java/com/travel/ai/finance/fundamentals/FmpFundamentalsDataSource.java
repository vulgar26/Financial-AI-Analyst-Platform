package com.travel.ai.finance.fundamentals;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Financial Modeling Prep (FMP) 真实基本面数据源。
 *
 * <p>调用 FMP stable 版两个端点并合并为统一 {@link FundamentalsSnapshot}：</p>
 * <ul>
 *   <li>{@code /profile?symbol=AAPL} —— 公司概览：名称、价格、市值、行业、货币</li>
 *   <li>{@code /ratios-ttm?symbol=AAPL} —— TTM 比率：PE、PB、PS、PEG、净利率、毛利率、负债权益比、流动比率</li>
 * </ul>
 *
 * <p><b>坏数据处理</b>：symbol 不存在 / 字段缺失 / 限额 / key 失效都返回
 * {@link FundamentalsSnapshot#unavailable}（由 meta 归因），不抛异常、不编造数值。
 * 仅传输级异常向上抛，交工具治理（超时 / 熔断）处理。</p>
 */
public class FmpFundamentalsDataSource implements FundamentalsDataSource {

    private static final Logger log = LoggerFactory.getLogger(FmpFundamentalsDataSource.class);
    private static final String SOURCE = "fmp";

    private final RestClient restClient;
    private final String apiKey;

    /**
     * @param restClient 已配置好 baseUrl / 超时的 RestClient（由配置装配注入，测试可注入 stub）
     * @param apiKey     FMP API key
     */
    public FmpFundamentalsDataSource(RestClient restClient, String apiKey) {
        this.restClient = restClient;
        this.apiKey = apiKey;
    }

    @Override
    public FundamentalsSnapshot fetch(String symbol) {
        String sym = symbol == null ? "" : symbol.trim().toUpperCase();
        if (sym.isEmpty()) {
            return FundamentalsSnapshot.unavailable(sym,
                    DataSourceMeta.unavailable(SOURCE, Instant.now(), "empty_symbol"));
        }

        JsonNode profile = firstElement(getArray("/profile", sym));
        if (profile == null) {
            // profile 取不到 = symbol 无效 / 限额 / key 问题，整体不可用。
            return FundamentalsSnapshot.unavailable(sym,
                    DataSourceMeta.unavailable(SOURCE, Instant.now(), "profile_not_found_or_unavailable"));
        }
        JsonNode ratios = firstElement(getArray("/ratios-ttm", sym)); // 可能 null，比率缺失不致命

        String currency = text(profile, "currency");
        return new FundamentalsSnapshot(
                sym,
                text(profile, "companyName"),
                text(profile, "exchange"),
                text(profile, "industry"),
                text(profile, "sector"),
                decimal(profile, "price"),
                decimal(profile, "marketCap"),
                decimal(ratios, "priceToEarningsRatioTTM"),
                decimal(ratios, "priceToBookRatioTTM"),
                decimal(ratios, "priceToSalesRatioTTM"),
                decimal(ratios, "priceToEarningsGrowthRatioTTM"),
                decimal(ratios, "netProfitMarginTTM"),
                decimal(ratios, "grossProfitMarginTTM"),
                decimal(ratios, "debtToEquityRatioTTM"),
                decimal(ratios, "currentRatioTTM"),
                DataSourceMeta.ok(SOURCE, Instant.now(), currency)
        );
    }

    /** GET {endpoint}?symbol=&apikey= 返回 JSON 数组；任何异常返回 null（由调用方归因）。 */
    private JsonNode getArray(String endpoint, String symbol) {
        try {
            return restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(endpoint)
                            .queryParam("symbol", symbol)
                            .queryParam("apikey", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            // 传输 / 反序列化失败：记录后返回 null，让上层归因为 unavailable，而非抛断链路。
            log.warn("[fmp] fetch failed endpoint={} symbol={} err={}", endpoint, symbol, e.toString());
            return null;
        }
    }

    private static JsonNode firstElement(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        return array.get(0);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText();
        return (s == null || s.isBlank()) ? null : s;
    }

    /** 安全取数值字段 → BigDecimal；缺失 / null / 非数值 → null（区分「没有」与「为零」）。 */
    private static BigDecimal decimal(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.decimalValue();
    }
}
