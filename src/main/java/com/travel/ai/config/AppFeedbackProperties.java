package com.travel.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 用户反馈（P1-3）：{@code app.feedback.*}。
 */
@ConfigurationProperties(prefix = "app.feedback")
public class AppFeedbackProperties {

    /**
     * 可选长评最大字符数（超出部分截断落库）。
     */
    private int maxCommentChars = 2000;

    /**
     * Page-size limit for feedback listing.
     * Route: {@code GET /analysis/feedback}; finance alias: {@code GET /finance/feedback}.
     */
    // Applies to /analysis/feedback and the /finance/feedback alias.
    private int maxPageSize = 100;

    public int getMaxCommentChars() {
        return maxCommentChars;
    }

    public void setMaxCommentChars(int maxCommentChars) {
        this.maxCommentChars = Math.max(256, maxCommentChars);
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = Math.max(1, Math.min(500, maxPageSize));
    }
}
