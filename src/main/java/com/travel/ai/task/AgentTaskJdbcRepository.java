package com.travel.ai.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AgentTaskJdbcRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AgentTaskJdbcRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public AgentTaskRow insert(
            UUID taskId,
            String userId,
            String requestId,
            AgentTaskType taskType,
            String idempotencyKey,
            JsonNode payload,
            int maxRetries
    ) {
        jdbcTemplate.update("""
                        INSERT INTO agent_task (
                            task_id, request_id, user_id, task_type, idempotency_key, status,
                            payload_json, retry_count, max_retries, next_run_at,
                            created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 0, ?, now(), now(), now())
                        """,
                taskId,
                blankToNull(requestId),
                userId,
                taskType.name(),
                blankToNull(idempotencyKey),
                AgentTaskStatus.PENDING.name(),
                toJson(payload),
                Math.max(0, maxRetries)
        );
        return findByTaskIdAndUserId(taskId, userId)
                .orElseThrow(() -> new IllegalStateException("created task not found: " + taskId));
    }

    public Optional<AgentTaskRow> findByIdempotencyKey(String userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return queryOne("""
                        SELECT * FROM agent_task
                        WHERE user_id = ? AND idempotency_key = ?
                        """,
                userId,
                idempotencyKey.trim());
    }

    public Optional<AgentTaskRow> findByTaskIdAndUserId(UUID taskId, String userId) {
        return queryOne("""
                        SELECT * FROM agent_task
                        WHERE task_id = ? AND user_id = ?
                        """,
                taskId,
                userId);
    }

    public Optional<AgentTaskRow> findByTaskId(UUID taskId) {
        return queryOne("SELECT * FROM agent_task WHERE task_id = ?", taskId);
    }

    @Transactional
    public List<AgentTaskRow> claimDueTasks(String leaseOwner, long leaseSeconds, int batchSize) {
        List<UUID> ids = jdbcTemplate.query("""
                        SELECT task_id
                        FROM agent_task
                        WHERE status = 'PENDING'
                          AND next_run_at <= now()
                        ORDER BY created_at ASC
                        LIMIT ?
                        FOR UPDATE SKIP LOCKED
                        """,
                (rs, rowNum) -> (UUID) rs.getObject("task_id"),
                Math.max(1, batchSize));
        if (ids.isEmpty()) {
            return List.of();
        }
        for (UUID id : ids) {
            jdbcTemplate.update("""
                            UPDATE agent_task
                            SET status = 'RUNNING',
                                lease_owner = ?,
                                lease_until = now() + (?::int * interval '1 second'),
                                updated_at = now()
                            WHERE task_id = ?
                              AND status = 'PENDING'
                            """,
                    leaseOwner,
                    Math.max(1L, leaseSeconds),
                    id);
            insertEvent(id, "TASK_RUNNING", objectMapper.createObjectNode().put("worker_id", leaseOwner));
        }
        return ids.stream()
                .map(this::findByTaskId)
                .flatMap(Optional::stream)
                .toList();
    }

    public Optional<AgentTaskRow> markSucceeded(UUID taskId, JsonNode result, String workerId, long latencyMs) {
        jdbcTemplate.update("""
                        UPDATE agent_task
                        SET status = 'SUCCEEDED',
                            result_json = ?::jsonb,
                            error_message = NULL,
                            lease_owner = NULL,
                            lease_until = NULL,
                            updated_at = now()
                        WHERE task_id = ?
                          AND status = 'RUNNING'
                        """,
                toJson(result),
                taskId);
        insertEvent(taskId, "TASK_SUCCEEDED", objectMapper.createObjectNode()
                .put("worker_id", workerId)
                .put("latency_ms", latencyMs));
        return findByTaskId(taskId);
    }

    public Optional<AgentTaskRow> markFailedOrRetry(UUID taskId, String errorMessage, String workerId, long latencyMs) {
        AgentTaskRow row = findByTaskId(taskId).orElseThrow(() -> new IllegalArgumentException("task not found: " + taskId));
        boolean retry = row.retryCount() < row.maxRetries();
        if (retry) {
            long backoffSeconds = Math.min(60L, 5L * (1L << Math.min(row.retryCount(), 4)));
            jdbcTemplate.update("""
                            UPDATE agent_task
                            SET status = 'PENDING',
                                retry_count = retry_count + 1,
                                error_message = ?,
                                next_run_at = now() + (?::int * interval '1 second'),
                                lease_owner = NULL,
                                lease_until = NULL,
                                updated_at = now()
                            WHERE task_id = ?
                              AND status = 'RUNNING'
                            """,
                    truncate(errorMessage),
                    backoffSeconds,
                    taskId);
            insertEvent(taskId, "TASK_RETRY", objectMapper.createObjectNode()
                    .put("worker_id", workerId)
                    .put("latency_ms", latencyMs)
                    .put("error_message", truncate(errorMessage))
                    .put("backoff_seconds", backoffSeconds));
        } else {
            jdbcTemplate.update("""
                            UPDATE agent_task
                            SET status = 'FAILED',
                                error_message = ?,
                                lease_owner = NULL,
                                lease_until = NULL,
                                updated_at = now()
                            WHERE task_id = ?
                              AND status = 'RUNNING'
                            """,
                    truncate(errorMessage),
                    taskId);
            insertEvent(taskId, "TASK_FAILED", objectMapper.createObjectNode()
                    .put("worker_id", workerId)
                    .put("latency_ms", latencyMs)
                    .put("error_message", truncate(errorMessage)));
        }
        return findByTaskId(taskId);
    }

    public int cancelPending(UUID taskId, String userId) {
        int updated = jdbcTemplate.update("""
                        UPDATE agent_task
                        SET status = 'CANCELLED',
                            lease_owner = NULL,
                            lease_until = NULL,
                            updated_at = now()
                        WHERE task_id = ?
                          AND user_id = ?
                          AND status = 'PENDING'
                        """,
                taskId,
                userId);
        if (updated > 0) {
            insertEvent(taskId, "TASK_CANCELLED", objectMapper.createObjectNode());
        }
        return updated;
    }

    public List<AgentTaskRow> recoverExpiredLeases(String workerId) {
        List<UUID> expired = jdbcTemplate.query("""
                        SELECT task_id
                        FROM agent_task
                        WHERE status = 'RUNNING'
                          AND lease_until < now()
                        """,
                (rs, rowNum) -> (UUID) rs.getObject("task_id"));
        List<AgentTaskRow> recoveredRows = new java.util.ArrayList<>();
        for (UUID taskId : expired) {
            AgentTaskRow row = findByTaskId(taskId).orElse(null);
            if (row == null) {
                continue;
            }
            boolean retry = row.retryCount() < row.maxRetries();
            if (retry) {
                int updated = jdbcTemplate.update("""
                                UPDATE agent_task
                                SET status = 'PENDING',
                                    retry_count = retry_count + 1,
                                    error_message = 'task lease expired',
                                    next_run_at = now() + interval '30 seconds',
                                    lease_owner = NULL,
                                    lease_until = NULL,
                                    updated_at = now()
                                WHERE task_id = ?
                                  AND status = 'RUNNING'
                                  AND lease_until < now()
                                """,
                        taskId);
                if (updated > 0) {
                    insertEvent(taskId, "TASK_LEASE_EXPIRED_RETRY", objectMapper.createObjectNode()
                            .put("worker_id", workerId));
                    findByTaskId(taskId).ifPresent(recoveredRows::add);
                }
            } else {
                int updated = jdbcTemplate.update("""
                                UPDATE agent_task
                                SET status = 'FAILED',
                                    error_message = 'task lease expired',
                                    lease_owner = NULL,
                                    lease_until = NULL,
                                    updated_at = now()
                                WHERE task_id = ?
                                  AND status = 'RUNNING'
                                  AND lease_until < now()
                                """,
                        taskId);
                if (updated > 0) {
                    insertEvent(taskId, "TASK_LEASE_EXPIRED_FAILED", objectMapper.createObjectNode()
                            .put("worker_id", workerId));
                    findByTaskId(taskId).ifPresent(recoveredRows::add);
                }
            }
        }
        return recoveredRows;
    }

    private Optional<AgentTaskRow> queryOne(String sql, Object... args) {
        List<AgentTaskRow> rows = jdbcTemplate.query(sql, rowMapper(), args);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    private org.springframework.jdbc.core.RowMapper<AgentTaskRow> rowMapper() {
        return (rs, rowNum) -> {
            try {
                String result = rs.getString("result_json");
                return new AgentTaskRow(
                        (UUID) rs.getObject("task_id"),
                        rs.getString("request_id"),
                        rs.getString("user_id"),
                        AgentTaskType.valueOf(rs.getString("task_type")),
                        rs.getString("idempotency_key"),
                        AgentTaskStatus.valueOf(rs.getString("status")),
                        objectMapper.readTree(rs.getString("payload_json")),
                        result == null ? null : objectMapper.readTree(result),
                        rs.getInt("retry_count"),
                        rs.getInt("max_retries"),
                        rs.getString("error_message"),
                        rs.getObject("next_run_at", OffsetDateTime.class),
                        rs.getString("lease_owner"),
                        rs.getObject("lease_until", OffsetDateTime.class),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getObject("updated_at", OffsetDateTime.class)
                );
            } catch (Exception e) {
                throw new IllegalStateException("agent_task row mapping failed", e);
            }
        };
    }

    private void insertEvent(UUID taskId, String eventType, JsonNode event) {
        AgentTaskRow row = findByTaskId(taskId).orElse(null);
        if (row == null) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode enriched = objectMapper.createObjectNode();
            if (event != null && event.isObject()) {
                enriched.setAll((com.fasterxml.jackson.databind.node.ObjectNode) event);
            }
            if (row.requestId() != null && !row.requestId().isBlank()) {
                enriched.put("request_id", row.requestId());
            }
            enriched.put("task_id", String.valueOf(row.taskId()));
            enriched.put("task_type", row.taskType().name());
            enriched.put("status", row.status().name());
            enriched.put("retry_count", row.retryCount());
            jdbcTemplate.update("""
                            INSERT INTO agent_task_event (event_id, task_id, user_id, event_type, event_json, created_at)
                            VALUES (?, ?, ?, ?, ?::jsonb, now())
                            """,
                    UUID.randomUUID(),
                    taskId,
                    row.userId(),
                    eventType,
                    toJson(enriched));
        } catch (DataAccessException ignored) {
            // Task state is authoritative; event logging is best-effort in V1.
        }
    }

    private String toJson(JsonNode jsonNode) {
        try {
            return objectMapper.writeValueAsString(jsonNode == null ? objectMapper.createObjectNode() : jsonNode);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid task json", e);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
