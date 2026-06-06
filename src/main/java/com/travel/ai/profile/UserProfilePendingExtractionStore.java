package com.travel.ai.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 将「从对话抽取的待确认画像」暂存 Redis，供 {@code GET/POST/DELETE …/pending-extraction} 使用。
 */
@Component
public class UserProfilePendingExtractionStore {

    private static final String KEY_PREFIX = "finance:profile:pending:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public UserProfilePendingExtractionStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    private static String key(String username, String conversationId) {
        return KEY_PREFIX + username + ":" + conversationId;
    }

    public void save(String username, String conversationId, PendingPayload payload, Duration ttl) {
        try {
            ObjectNode root = objectMapper.createObjectNode();
            root.set("suggestedPatch", payload.suggestedPatch().deepCopy());
            root.set("mergedPreview", payload.mergedPreview().deepCopy());
            root.put("createdAtEpochMs", payload.createdAtEpochMs());
            stringRedisTemplate.opsForValue().set(key(username, conversationId), objectMapper.writeValueAsString(root), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize pending extraction", e);
        }
    }

    public Optional<PendingPayload> load(String username, String conversationId) {
        String json = stringRedisTemplate.opsForValue().get(key(username, conversationId));
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            ObjectNode root = (ObjectNode) objectMapper.readTree(json);
            ObjectNode suggested = root.has("suggestedPatch") && root.get("suggestedPatch").isObject()
                    ? (ObjectNode) root.get("suggestedPatch")
                    : objectMapper.createObjectNode();
            ObjectNode merged = root.has("mergedPreview") && root.get("mergedPreview").isObject()
                    ? (ObjectNode) root.get("mergedPreview")
                    : objectMapper.createObjectNode();
            long ts = root.path("createdAtEpochMs").asLong(0L);
            return Optional.of(new PendingPayload(suggested, merged, ts));
        } catch (Exception e) {
            stringRedisTemplate.delete(key(username, conversationId));
            return Optional.empty();
        }
    }

    public void delete(String username, String conversationId) {
        stringRedisTemplate.delete(key(username, conversationId));
    }

    public record PendingPayload(ObjectNode suggestedPatch, ObjectNode mergedPreview, long createdAtEpochMs) {
    }
}
