package com.travel.ai.finance.fundamentals;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Financial Modeling Prep (FMP) 基本面数据源配置（{@code app.finance.fmp.*}）。
 *
 * <p>API key 走环境变量 {@code FMP_API_KEY} 注入，绝不写入代码 / 提交 / 日志。
 * key 为空时系统自动降级到 {@link MockFundamentalsDataSource}（见配置装配），
 * 使项目无 key 也能启动和测试。</p>
 */
@ConfigurationProperties(prefix = "app.finance.fmp")
public class FmpProperties {

    /** FMP API key；空表示未配置 → 降级 mock。来自 {@code ${FMP_API_KEY:}}。 */
    private String apiKey = "";

    /** FMP 新版（stable）API 基础地址。 */
    private String baseUrl = "https://financialmodelingprep.com/stable";

    /** 是否启用真实数据源；false 或 key 为空时降级 mock。 */
    private boolean enabled = true;

    /** 单个 symbol 快照的缓存 TTL（秒）；基本面变化以天 / 季计，缓存防止反复查同票烧额度。 */
    private long cacheTtlSeconds = 21600; // 6 小时

    /** 缓存最大条目数。 */
    private long cacheMaxSize = 500;

    /** HTTP 连接 / 读取超时（毫秒）。 */
    private int timeoutMs = 5000;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public long getCacheMaxSize() {
        return cacheMaxSize;
    }

    public void setCacheMaxSize(long cacheMaxSize) {
        this.cacheMaxSize = cacheMaxSize;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }
}
