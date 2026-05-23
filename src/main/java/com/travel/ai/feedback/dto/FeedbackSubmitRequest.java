package com.travel.ai.feedback.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.ArrayList;
import java.util.List;

/**
 * Feedback request body shared by preferred {@code /analysis/feedback}, finance alias
 * {@code /finance/feedback}, and legacy-compatible {@code /travel/feedback}.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
// Request body shared by preferred /analysis/feedback, /finance/feedback alias, and legacy-compatible /travel/feedback.
public class FeedbackSubmitRequest {

    private String conversationId;
    /** {@code up} / {@code down}，与 {@code rating} / {@code comment} 至少填一类（见表约束）。 */
    private String thumb;
    /** 1–5，可选。 */
    private Integer rating;
    private String comment;
    /** 与评测集 / 离线归因对齐的可选 case id。 */
    private String evalCaseId;
    /** 可选标签列表（如 {@code rag/empty}、{@code cost/probe}），服务端会裁剪长度与条数。 */
    private List<String> evalTags = new ArrayList<>();
    /** 与某次 Agent / SSE 的 request_id 对齐（可选）。 */
    private String requestId;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getThumb() {
        return thumb;
    }

    public void setThumb(String thumb) {
        this.thumb = thumb;
    }

    public Integer getRating() {
        return rating;
    }

    public void setRating(Integer rating) {
        this.rating = rating;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public String getEvalCaseId() {
        return evalCaseId;
    }

    public void setEvalCaseId(String evalCaseId) {
        this.evalCaseId = evalCaseId;
    }

    public List<String> getEvalTags() {
        return evalTags;
    }

    public void setEvalTags(List<String> evalTags) {
        this.evalTags = evalTags != null ? evalTags : new ArrayList<>();
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }
}
