package com.travel.ai.conversation;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 将 {@code conversationId} 登记到当前登录用户，供 {@code app.conversation.require-registration=true} 时校验归属。
 */
@Service
public class ConversationRegistry {

    static final String KEY_PREFIX = "finance:chat:convreg:";
    private static final long TTL_DAYS = 30L;

    private final StringRedisTemplate stringRedisTemplate;

    public ConversationRegistry(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static String currentPrincipal() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "anonymous";
        }
        String name = auth.getName();
        return name == null || name.isBlank() ? "anonymous" : name;
    }

    private String key(String user, String conversationId) {
        return KEY_PREFIX + user + ":" + conversationId;
    }

    /**
     * 生成 UUID 并登记到当前用户。
     *
     * @return 新会话 id（无大写要求，与路径变量校验一致）
     */
    public String createAndRegister() {
        String id = UUID.randomUUID().toString();
        register(id);
        return id;
    }

    /** 登记已有 id（与 {@link #createAndRegister()} 共用 Redis TTL）。 */
    public void register(String conversationId) {
        if (!ConversationIdValidator.isValid(conversationId)) {
            throw new IllegalArgumentException("invalid conversationId");
        }
        String user = currentPrincipal();
        stringRedisTemplate.opsForValue().set(key(user, conversationId), "1", TTL_DAYS, TimeUnit.DAYS);
    }

    public boolean isRegistered(String conversationId) {
        if (!ConversationIdValidator.isValid(conversationId)) {
            return false;
        }
        String user = currentPrincipal();
        Boolean exists = stringRedisTemplate.hasKey(key(user, conversationId));
        return Boolean.TRUE.equals(exists);
    }
}
