package com.travel.ai.finance.fundamentals;

import java.time.Instant;

/**
 * 一份基本面快照的来源与时效元信息（合规边界的工程载体）。
 *
 * <p>这些字段不是给 LLM 拼字符串用的临时信息，而是一等公民：
 * 标的档案要存「数据何时取的」、GUARD 要检查「是否过期」、巡检要判断「要不要刷新」，
 * 都依赖结构化的元信息，所以独立成对象而非塞进文本。</p>
 *
 * @param source            数据来源标识，如 {@code fmp} / {@code mock}
 * @param asOf              数据获取时刻（系统取数时间，非财报报告期）
 * @param mockMode          是否为 mock / 占位数据（true 时必须在输出中明确标注，不得当真实行情）
 * @param currency          数据计价货币，如 {@code USD}；未知为 null
 * @param unavailableReason 数据不可用 / 缺失的原因（如 symbol 不存在、限额、字段缺失）；正常为 null
 */
public record DataSourceMeta(
        String source,
        Instant asOf,
        boolean mockMode,
        String currency,
        String unavailableReason
) {

    public DataSourceMeta {
        source = source != null ? source : "unknown";
        asOf = asOf != null ? asOf : Instant.EPOCH;
    }

    /** 数据正常获取时的元信息。 */
    public static DataSourceMeta ok(String source, Instant asOf, String currency) {
        return new DataSourceMeta(source, asOf, false, currency, null);
    }

    /** mock / 占位数据的元信息。 */
    public static DataSourceMeta mock(Instant asOf) {
        return new DataSourceMeta("mock", asOf, true, null, null);
    }

    /** 数据不可用（symbol 不存在、限额、上游失败等）的元信息。 */
    public static DataSourceMeta unavailable(String source, Instant asOf, String reason) {
        return new DataSourceMeta(source, asOf, false, null, reason);
    }

    /** 数据是否可用（无 unavailableReason 即为可用）。 */
    public boolean available() {
        return unavailableReason == null || unavailableReason.isBlank();
    }
}
