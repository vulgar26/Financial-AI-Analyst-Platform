package com.travel.ai.finance.fundamentals;

import java.math.BigDecimal;

/**
 * 一个标的在某一时刻取到的基本面快照（瞬时、只读、来自数据源）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>持久化无知</b>：纯领域对象，不带任何 JPA / 框架注解，不为「好存」妥协字段。
 *       将来要落库时新增 JDBC adapter，本对象一字不改。</li>
 *   <li><b>金融数值用 {@link BigDecimal}</b>：钱与比率绝不可用浮点（精度灾难）。</li>
 *   <li><b>所有指标可空</b>：真实数据一定会缺（未盈利公司无 PE、新股无历史）。
 *       {@code null}（没有）与 {@code BigDecimal.ZERO}（确实为零）含义不同，消费方需区分。</li>
 *   <li><b>这是「瞬时快照」，不是「标的档案」</b>：不承担长期记录 / 用户判断 / 历史序列，
 *       那是未来「标的档案」聚合的职责（档案会引用多个快照）。</li>
 * </ul>
 *
 * <p>字段对应价值投资三问：是谁（标识）、贵不贵（估值）、强不强 / 健不健康（质量 / 健康）。
 * 第一块砖只放最核心的几个，字段随规则需求增长，不预先大而全。</p>
 */
public record FundamentalsSnapshot(
        // —— 标识：这是谁 ——
        String symbol,
        String companyName,
        String exchange,
        String industry,
        String sector,

        // —— 价格与规模 ——
        BigDecimal price,
        BigDecimal marketCap,

        // —— 估值：贵不贵（来自 ratios-ttm）——
        BigDecimal peRatio,        // 市盈率
        BigDecimal pbRatio,        // 市净率
        BigDecimal psRatio,        // 市销率
        BigDecimal pegRatio,       // PE / 增长率，综合估值与增长

        // —— 质量：赚钱能力强不强 ——
        BigDecimal netProfitMargin,    // 净利率
        BigDecimal grossProfitMargin,  // 毛利率

        // —— 健康：会不会暴雷 ——
        BigDecimal debtToEquity,   // 负债权益比
        BigDecimal currentRatio,   // 流动比率

        // —— 来源与时效 ——
        DataSourceMeta meta
) {

    public FundamentalsSnapshot {
        symbol = symbol != null ? symbol : "";
        meta = meta != null ? meta : DataSourceMeta.unavailable("unknown", null, "missing_meta");
    }

    /**
     * 构造一个「不可用」快照：symbol 取不到数据时使用（symbol 不存在、限额、上游失败）。
     * 所有指标为 null，由 meta 携带原因；渲染层据此输出降级说明而非编造数值。
     */
    public static FundamentalsSnapshot unavailable(String symbol, DataSourceMeta meta) {
        return new FundamentalsSnapshot(
                symbol, null, null, null, null,
                null, null,
                null, null, null, null,
                null, null,
                null, null,
                meta
        );
    }

    /** 数据是否可用（委托给 meta）。 */
    public boolean available() {
        return meta.available();
    }
}
