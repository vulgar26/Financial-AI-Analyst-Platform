package com.travel.ai.finance.fundamentals;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;

/**
 * 给任意 {@link FundamentalsDataSource} 套一层 Caffeine 缓存（装饰器）。
 *
 * <p>基本面数据变化以天 / 季计，但开发 / 测试 / 反复问同一只票会快速烧掉 API 免费额度
 * （FMP 免费档约 250 次/天）。按 symbol 缓存若干小时，大幅降低真实调用次数。</p>
 *
 * <p>缓存与数据源正交：换数据源不影响缓存逻辑，去掉缓存也不影响数据源。</p>
 */
public class CachingFundamentalsDataSource implements FundamentalsDataSource {

    private final FundamentalsDataSource delegate;
    private final Cache<String, FundamentalsSnapshot> cache;

    public CachingFundamentalsDataSource(FundamentalsDataSource delegate, Duration ttl, long maxSize) {
        this.delegate = delegate;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(ttl)
                .maximumSize(maxSize)
                .build();
    }

    @Override
    public FundamentalsSnapshot fetch(String symbol) {
        String key = symbol == null ? "" : symbol.trim().toUpperCase();
        FundamentalsSnapshot cached = cache.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        FundamentalsSnapshot fresh = delegate.fetch(symbol);
        // 只缓存「可用」结果：不可用（限额 / 临时失败）不缓存，避免把短暂故障固化数小时。
        if (fresh != null && fresh.available()) {
            cache.put(key, fresh);
        }
        return fresh;
    }
}
