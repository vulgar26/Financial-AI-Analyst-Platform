package com.travel.ai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.ai.controller.dto.AgentTaskCreateRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
public class AgentTaskService {

    private final AgentTaskJdbcRepository repository;
    private final AgentTaskProperties properties;

    public AgentTaskService(AgentTaskJdbcRepository repository, AgentTaskProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public AgentTaskRow create(AgentTaskCreateRequest request) {
        String userId = currentUser();
        AgentTaskType taskType = parseType(request != null ? request.getTaskType() : null);
        String idempotencyKey = request != null ? request.getIdempotencyKey() : null;
        JsonNode payload = request != null ? request.getPayload() : null;

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<AgentTaskRow> existing = repository.findByIdempotencyKey(userId, idempotencyKey);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        UUID taskId = UUID.randomUUID();
        try {
            return repository.insert(
                    taskId,
                    userId,
                    taskType,
                    idempotencyKey,
                    payload,
                    properties.getDefaultMaxRetries());
        } catch (DuplicateKeyException e) {
            return repository.findByIdempotencyKey(userId, idempotencyKey)
                    .orElseThrow(() -> e);
        }
    }

    public Optional<AgentTaskRow> findMine(UUID taskId) {
        return repository.findByTaskIdAndUserId(taskId, currentUser());
    }

    public Optional<AgentTaskRow> cancelMine(UUID taskId) {
        String userId = currentUser();
        repository.cancelPending(taskId, userId);
        return repository.findByTaskIdAndUserId(taskId, userId);
    }

    private AgentTaskType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("task_type is required");
        }
        try {
            return AgentTaskType.valueOf(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unsupported task_type: " + raw);
        }
    }

    private static String currentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "anonymous";
    }
}

