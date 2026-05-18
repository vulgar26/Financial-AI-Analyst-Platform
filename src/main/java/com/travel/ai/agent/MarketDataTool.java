package com.travel.ai.agent;

import com.travel.ai.tools.GovernedAgentTool;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Mock market data tool placeholder.
 * <p>
 * This does not call any real financial API. It only exercises the existing
 * tool governance path for financial-analysis semantics.
 */
@Component
public class MarketDataTool implements GovernedAgentTool {

    private static final Pattern PE_TOKEN_PATTERN = Pattern.compile("(^|[^a-z0-9])pe([^a-z0-9]|$)");

    @Override
    public String name() {
        return "market_data";
    }

    @Override
    public boolean shouldHandle(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String q = userMessage.toLowerCase(Locale.ROOT);
        return q.contains("行情")
                || q.contains("股价")
                || q.contains("市场数据")
                || q.contains("p/e")
                || PE_TOKEN_PATTERN.matcher(q).find()
                || q.contains("成交量")
                || q.contains("涨跌")
                || q.contains("估值")
                || q.contains("market")
                || q.contains("quote")
                || q.contains("price")
                || q.contains("volume")
                || q.contains("ticker");
    }

    @Override
    public String resolveInput(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "UNKNOWN";
        }
        String upper = userMessage.toUpperCase(Locale.ROOT);
        for (String token : upper.split("[^A-Z0-9.]+")) {
            if (token.matches("[A-Z]{1,5}(\\.[A-Z]{1,3})?")) {
                return token;
            }
        }
        return "MOCK";
    }

    @Override
    public String observe(String input) {
        String symbol = input == null || input.isBlank() ? "MOCK" : input;
        Instant timestamp = Instant.now();
        return """
                mockMode=true
                mock_market_data=true
                data_source=local_mock
                symbol=%s
                timestamp=%s
                as_of=%s
                freshness=mock_non_realtime
                tradable=false
                real_financial_api_used=false
                trading_capability_used=false
                not_for_trading=true
                last_price=123.45
                change_pct=0.00
                note=This is placeholder market data for agent workflow validation only. It is not real-time data and must not be used for trading.
                """.formatted(symbol, timestamp, timestamp);
    }
}
