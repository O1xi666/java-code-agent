package com.example.javacodeagent.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 短期记忆（会话级）
 *
 * <p>存储单会话全量对话历史，不设上限。
 * 7 天 TTL，匹配用户"过几天回来继续同一会话"的需求。
 *
 * <p>推理窗口大小在 {@link StockAgent} 中通过
 * {@link #getLastMessages(String, int)} 控制，不做存储级裁剪。
 */
@Component
public class SessionMemory {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "session:memory:";
    private static final long TTL_DAYS = 7;

    /**
     * 获取最近的 N 条消息（用于构建工作记忆的推理窗口）。
     */
    @SuppressWarnings("unchecked")
    public List<ChatMessage> getLastMessages(String sessionId, int count) {
        String key = KEY_PREFIX + sessionId;
        try {
            List<ChatMessage> all = (List<ChatMessage>) redisTemplate.opsForValue().get(key);
            if (all == null || all.isEmpty()) return List.of();
            int from = Math.max(0, all.size() - count);
            return all.subList(from, all.size());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 持久化一条消息到全量会话历史。
     */
    @SuppressWarnings("unchecked")
    public void addMessage(String sessionId, ChatMessage message) {
        String key = KEY_PREFIX + sessionId;
        try {
            List<ChatMessage> all = (List<ChatMessage>) redisTemplate.opsForValue().get(key);
            if (all == null) {
                all = new ArrayList<>();
            }
            all.add(message);
            redisTemplate.opsForValue().set(key, all);
            redisTemplate.expire(key, TTL_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            System.err.println("SessionMemory 保存失败: " + e.getMessage());
        }
    }

    public void saveUserMessage(String sessionId, String content) {
        addMessage(sessionId, new UserMessage(content));
    }

    public void saveAssistantMessage(String sessionId, String content) {
        addMessage(sessionId, new AiMessage(content));
    }

    public void clear(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public boolean hasSession(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }
}
