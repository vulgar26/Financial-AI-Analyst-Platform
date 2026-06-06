package com.travel.ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.ai.dto.SimpleMsg;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class RedisChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemory.class);
    private static final String KEY_PREFIX = "finance:chat:memory:";
    private static final int MAX_MESSAGES = 20;
    private static final long EXPIRE_DAYS = 1;

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = new ObjectMapper();
    }

    private String buildKey(String conversationId) {
        var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
        String username = context.getAuthentication() != null
                ? context.getAuthentication().getName()
                : "anonymous";
        return buildKey(username, conversationId);
    }

    /**
     * 显式用户名（供删除画像等路径：不依赖当前 {@code SecurityContext} 解析出的主体名）。
     */
    public static String buildKey(String username, String conversationId) {
        String u = username != null && !username.isBlank() ? username : "anonymous";
        return KEY_PREFIX + u + ":" + conversationId;
    }

    /**
     * 删除指定用户在某会话下的 Redis 短期记忆（与 {@link #clear} 等价，但用户名显式传入）。
     */
    public void clearForUser(String username, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        stringRedisTemplate.delete(buildKey(username, conversationId));
    }

    /**
     * 扫描并删除 {@code finance:chat:memory:{username}:*} 下全部键（慎用；用于删除画像时可选清理会话）。
     *
     * @return 删除的键数量
     */
    public int deleteAllConversationsForUser(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        String pattern = KEY_PREFIX + username + ":*";
        ScanOptions options = ScanOptions.scanOptions().match(pattern).count(500).build();
        int n = 0;
        try (Cursor<String> cursor = stringRedisTemplate.scan(options)) {
            while (cursor.hasNext()) {
                stringRedisTemplate.delete(cursor.next());
                n++;
            }
        }
        return n;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        String key = buildKey(conversationId);

        List<SimpleMsg> simpleHistory = getSimpleHistory(conversationId);

        List<SimpleMsg> newSimpleList = messages.stream().map(msg -> {
            SimpleMsg sm = new SimpleMsg();
            sm.role = msg instanceof UserMessage ? "user" : "assistant";
            sm.content = msg.getText();
            return sm;
        }).collect(Collectors.toList());

        simpleHistory.addAll(newSimpleList);

        while (simpleHistory.size() > MAX_MESSAGES) {
            simpleHistory.remove(0);
        }

        try {
            String json = objectMapper.writeValueAsString(simpleHistory);
            stringRedisTemplate.opsForValue().set(key, json, EXPIRE_DAYS, TimeUnit.DAYS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("保存对话失败", e);
        }
    }

    // 读取简单消息列表（按 userId + conversationId 维度隔离）
    private List<SimpleMsg> getSimpleHistory(String conversationId) {
        String key = buildKey(conversationId);
        String json = stringRedisTemplate.opsForValue().get(key);
        log.debug("从Redis原始数据: {}", json);
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<SimpleMsg>>() {});
        } catch (Exception e) {
            // 旧脏数据直接清空，不报错
            log.error("历史数据格式异常，清空重建: {}", e.getMessage());
            stringRedisTemplate.delete(key);
            return new ArrayList<>();
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<SimpleMsg> simpleHistory = getSimpleHistory(conversationId);
        // 转回 SpringAI Message
        return simpleHistory.stream().map(sm -> {
            if ("user".equals(sm.role)) {
                return new UserMessage(sm.content);
            } else {
                return new AssistantMessage(sm.content);
            }
        }).collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(buildKey(conversationId));
    }
}