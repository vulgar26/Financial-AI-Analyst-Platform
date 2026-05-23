package com.travel.ai.controller.dto;

/**
 * Legacy-compatible {@code /travel/chat/{conversationId}} request body.
 * New clients should use {@code /analysis/chat/{conversationId}} or {@code /finance/chat/{conversationId}}
 * with the same JSON contract.
 */
// Legacy DTO name retained for /travel/** compatibility.
// New finance analyst routes use the same JSON contract through AnalysisChatRequest.
public class TravelChatRequest {

    private String query;

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }
}
