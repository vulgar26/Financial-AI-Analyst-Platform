package com.travel.ai.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.travel.ai.task.AgentTaskRow;

import java.time.OffsetDateTime;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AgentTaskResponse {

    @JsonProperty("task_id")
    private UUID taskId;
    @JsonProperty("request_id")
    private String requestId;
    @JsonProperty("user_id")
    private String userId;
    @JsonProperty("task_type")
    private String taskType;
    private String status;
    private JsonNode payload;
    private JsonNode result;
    @JsonProperty("retry_count")
    private int retryCount;
    @JsonProperty("max_retries")
    private int maxRetries;
    @JsonProperty("error_message")
    private String errorMessage;
    @JsonProperty("created_at")
    private OffsetDateTime createdAt;
    @JsonProperty("updated_at")
    private OffsetDateTime updatedAt;

    public static AgentTaskResponse from(AgentTaskRow row) {
        AgentTaskResponse r = new AgentTaskResponse();
        r.taskId = row.taskId();
        r.requestId = row.requestId();
        r.userId = row.userId();
        r.taskType = row.taskType().name();
        r.status = row.status().name();
        r.payload = row.payload();
        r.result = row.result();
        r.retryCount = row.retryCount();
        r.maxRetries = row.maxRetries();
        r.errorMessage = row.errorMessage();
        r.createdAt = row.createdAt();
        r.updatedAt = row.updatedAt();
        return r;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getUserId() {
        return userId;
    }

    public String getTaskType() {
        return taskType;
    }

    public String getStatus() {
        return status;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public JsonNode getResult() {
        return result;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
