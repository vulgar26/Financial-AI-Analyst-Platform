package com.travel.ai.agent;

import com.travel.ai.finance.fundamentals.FundamentalsDataSource;
import com.travel.ai.finance.fundamentals.FundamentalsSnapshot;
import com.travel.ai.finance.fundamentals.FundamentalsTextRenderer;
import com.travel.ai.tools.GovernedAgentTool;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 市场 / 基本面数据工具（受治理）。
 *
 * <p>职责边界（agent 语义）：判断要不要处理（{@link #shouldHandle}）、从用户消息抽取
 * 股票代码（{@link #resolveInput}）。真正的取数交给 {@link FundamentalsDataSource}
 * （外部 IO，可替换：真实 FMP / mock 降级），再由 {@link FundamentalsTextRenderer}
 * 渲染成给 LLM 的文本。</p>
 *
 * <p>未配置 {@code FMP_API_KEY} 时，注入的是 MockFundamentalsDataSource（降级），
 * 输出会标注 mock / 非实时 / 不可交易。</p>
 */
@Component
public class MarketDataTool implements GovernedAgentTool {

    private static final Pattern PE_TOKEN_PATTERN = Pattern.compile("(^|[^a-z0-9])pe([^a-z0-9]|$)");
    private static final java.util.Set<String> TICKER_STOP_WORDS = java.util.Set.of(
            "WHAT", "IS", "THE", "FOR", "AND", "OR", "PRICE", "QUOTE", "VOLUME", "MARKET", "TICKER", "EXPLAIN"
    );

    private final FundamentalsDataSource dataSource;

    public MarketDataTool(FundamentalsDataSource dataSource) {
        this.dataSource = dataSource;
    }

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
                || q.contains("基本面")
                || q.contains("p/e")
                || PE_TOKEN_PATTERN.matcher(q).find()
                || q.contains("成交量")
                || q.contains("涨跌")
                || q.contains("估值")
                || q.contains("market")
                || q.contains("quote")
                || q.contains("price")
                || q.contains("volume")
                || q.contains("ticker")
                || q.contains("fundamental");
    }

    @Override
    public String resolveInput(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "UNKNOWN";
        }
        String upper = userMessage.toUpperCase(Locale.ROOT);
        for (String token : upper.split("[^A-Z0-9.]+")) {
            if (token.matches("[A-Z]{1,5}(\\.[A-Z]{1,3})?") && !TICKER_STOP_WORDS.contains(token)) {
                return token;
            }
        }
        return "MOCK";
    }

    @Override
    public String observe(String input) {
        FundamentalsSnapshot snapshot = dataSource.fetch(input);
        return FundamentalsTextRenderer.render(snapshot);
    }
}
