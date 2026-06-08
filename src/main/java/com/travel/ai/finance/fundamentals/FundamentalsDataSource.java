package com.travel.ai.finance.fundamentals;

/**
 * 基本面数据源抽象：输入股票代码，返回统一结构的 {@link FundamentalsSnapshot}。
 *
 * <p>各家外部 API 的字段千差万别（FMP 叫 {@code pe}，Alpha Vantage 叫 {@code PERatio}），
 * 字段适配的脏活在各自实现里做掉，对上层只暴露统一结构。换源 / 加源时上层零感知。</p>
 *
 * <p>实现约定：<b>取不到数据不抛异常，返回 {@link FundamentalsSnapshot#unavailable}</b>
 * （symbol 不存在、限额、字段缺失等都是「正常返回里的坏数据」，由 meta 归因）。
 * 仅当发生真正的传输级异常时才抛出，交由上层工具治理（超时 / 熔断）处理。</p>
 */
public interface FundamentalsDataSource {

    /**
     * 拉取一个标的的基本面快照。
     *
     * @param symbol 股票代码，如 {@code AAPL}
     * @return 基本面快照；数据缺失时返回 {@link FundamentalsSnapshot#available()} 为 false 的快照
     */
    FundamentalsSnapshot fetch(String symbol);
}
