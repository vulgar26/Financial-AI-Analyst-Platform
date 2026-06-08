package com.travel.ai.finance.fundamentals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * 基本面数据源的装配：根据配置选真实源还是降级 mock。
 *
 * <p>决策：{@code app.finance.fmp.enabled=true} 且配置了 {@code FMP_API_KEY}
 * → 真实 {@link FmpFundamentalsDataSource}（外套 {@link CachingFundamentalsDataSource} 缓存）；
 * 否则 → {@link MockFundamentalsDataSource}（降级，使无 key 也能启动 / 测试）。</p>
 */
@Configuration
public class FundamentalsConfig {

    private static final Logger log = LoggerFactory.getLogger(FundamentalsConfig.class);

    @Bean
    public FundamentalsDataSource fundamentalsDataSource(FmpProperties props) {
        if (!props.isEnabled() || !props.hasApiKey()) {
            log.info("[fundamentals] 使用 MockFundamentalsDataSource（enabled={} hasApiKey={}）——未配置真实数据源，降级 mock。",
                    props.isEnabled(), props.hasApiKey());
            return new MockFundamentalsDataSource();
        }

        RestClient restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .build();
        FundamentalsDataSource real = new FmpFundamentalsDataSource(restClient, props.getApiKey());
        log.info("[fundamentals] 使用 FmpFundamentalsDataSource（baseUrl={}，缓存 ttl={}s maxSize={}）",
                props.getBaseUrl(), props.getCacheTtlSeconds(), props.getCacheMaxSize());
        return new CachingFundamentalsDataSource(
                real,
                Duration.ofSeconds(props.getCacheTtlSeconds()),
                props.getCacheMaxSize());
    }
}
