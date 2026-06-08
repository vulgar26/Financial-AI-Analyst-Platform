package com.travel.ai.finance.fundamentals;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 把 {@link FundamentalsSnapshot} 渲染成给 LLM 阅读的文本（结构 → 文本的单一出口）。
 *
 * <p>职责集中在一处：统一处理 null（输出「暂无数据」而非 0 或崩溃）、统一加合规标注
 * （数据来源 / 时点 / mock 提示 / 不构成投资建议）。要调「给 LLM 的措辞 / 格式」只改这里，
 * 不碰数据获取逻辑。</p>
 *
 * <p>沿用既有工具 {@code key=value} 风格，保持 prompt / 工具前言对工具输出的解析预期稳定。</p>
 */
public final class FundamentalsTextRenderer {

    private FundamentalsTextRenderer() {
    }

    private static final String NA = "暂无数据";

    public static String render(FundamentalsSnapshot s) {
        if (s == null) {
            return "fundamentals_available=false\nreason=null_snapshot\n";
        }
        DataSourceMeta meta = s.meta();

        // 数据不可用：明确降级说明，不编造数值。
        if (!s.available()) {
            return """
                    fundamentals_available=false
                    symbol=%s
                    data_source=%s
                    as_of=%s
                    reason=%s
                    note=未能取得该标的的基本面数据，不构成投资建议；请核实代码或稍后重试。
                    """.formatted(
                    nz(s.symbol()),
                    nz(meta.source()),
                    meta.asOf(),
                    nz(meta.unavailableReason()));
        }

        StringBuilder b = new StringBuilder();
        b.append("fundamentals_available=true\n");
        b.append("symbol=").append(nz(s.symbol())).append('\n');
        b.append("company_name=").append(nz(s.companyName())).append('\n');
        b.append("exchange=").append(nz(s.exchange())).append('\n');
        b.append("sector=").append(nz(s.sector())).append('\n');
        b.append("industry=").append(nz(s.industry())).append('\n');

        // 价格与规模
        b.append("price=").append(money(s.price())).append('\n');
        b.append("market_cap=").append(money(s.marketCap())).append('\n');
        b.append("currency=").append(nz(meta.currency())).append('\n');

        // 估值
        b.append("pe_ratio=").append(ratio(s.peRatio())).append('\n');
        b.append("pb_ratio=").append(ratio(s.pbRatio())).append('\n');
        b.append("ps_ratio=").append(ratio(s.psRatio())).append('\n');
        b.append("peg_ratio=").append(ratio(s.pegRatio())).append('\n');

        // 质量
        b.append("net_profit_margin=").append(pct(s.netProfitMargin())).append('\n');
        b.append("gross_profit_margin=").append(pct(s.grossProfitMargin())).append('\n');

        // 健康
        b.append("debt_to_equity=").append(ratio(s.debtToEquity())).append('\n');
        b.append("current_ratio=").append(ratio(s.currentRatio())).append('\n');

        // 来源与合规
        b.append("data_source=").append(nz(meta.source())).append('\n');
        b.append("as_of=").append(meta.asOf()).append('\n');
        b.append("mock_mode=").append(meta.mockMode()).append('\n');
        if (meta.mockMode()) {
            b.append("freshness=mock_non_realtime\n");
            b.append("tradable=false\n");
            b.append("note=占位数据，非实时、不可用于交易，仅用于链路验证。\n");
        } else {
            b.append("note=数据来自第三方财务数据源，可能存在延迟；为研究用途，不构成投资建议。\n");
        }
        return b.toString();
    }

    private static String nz(String v) {
        return (v == null || v.isBlank()) ? NA : v;
    }

    /** 金额：原值输出（保留整数 / 两位小数），null → 暂无。 */
    private static String money(BigDecimal v) {
        return v == null ? NA : v.stripTrailingZeros().toPlainString();
    }

    /** 比率：保留两位小数，null → 暂无。 */
    private static String ratio(BigDecimal v) {
        return v == null ? NA : v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 百分比：小数转百分数（0.2715 → 27.15%），null → 暂无。 */
    private static String pct(BigDecimal v) {
        return v == null ? NA
                : v.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }
}
