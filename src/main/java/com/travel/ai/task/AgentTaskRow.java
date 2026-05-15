package com.travel.ai.task;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentTaskRow(
        UUID taskId,
        String userId,
        AgentTaskType taskType,
        String idempotencyKey,
        AgentTaskStatus status,
        JsonNode payload,
        JsonNode result,
        int retryCount,
        int maxRetries,
        String errorMessage,
        OffsetDateTime nextRunAt,
        String leaseOwner,
        OffsetDateTime leaseUntil,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}

