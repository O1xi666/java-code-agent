package com.example.javacodeagent.service;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class ChatMemoryHolder {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String REDIS_KEY_PREFIX = "chat:memory:";
    private static final int MAX_MESSAGE_COUNT = 15;
    private static final long SESSION_EXPIRE_DAYS = 7;

    public ChatMemory getChatMemory(String sessionId) {
        String redisKey = REDIS_KEY_PREFIX + sessionId;
        try {
            List<ChatMessage> historyMessages = (List<ChatMessage>) redisTemplate.opsForValue().get(redisKey);
            MessageWindowChatMemory.Builder builder = MessageWindowChatMemory.builder()
                    .maxMessages(MAX_MESSAGE_COUNT)
                    .id(sessionId);
            if (historyMessages != null && !historyMessages.isEmpty()) {
                ChatMemory chatMemory = builder.build();
                for (ChatMessage message : historyMessages) {
                    chatMemory.add(message);
                }
                return createSyncedChatMemory(chatMemory, redisKey);
            }
            ChatMemory chatMemory = builder.build();
            return createSyncedChatMemory(chatMemory, redisKey);
        } catch (Exception e) {
            // Redis 反序列化失败，自动重建会话（正常行为）
            redisTemplate.delete(redisKey);
            ChatMemory chatMemory = MessageWindowChatMemory.builder()
                    .maxMessages(MAX_MESSAGE_COUNT)
                    .id(sessionId)
                    .build();
            return createSyncedChatMemory(chatMemory, redisKey);
        }
    }

    private ChatMemory createSyncedChatMemory(ChatMemory originalMemory, String redisKey) {
        return new ChatMemory() {
            @Override
            public String id() {
                return originalMemory.id().toString();
            }
            @Override
            public void add(ChatMessage message) {
                originalMemory.add(message);
                syncToRedis(redisKey, originalMemory.messages());
            }
            @Override
            public List<ChatMessage> messages() {
                return originalMemory.messages();
            }
            @Override
            public void clear() {
                originalMemory.clear();
                redisTemplate.delete(redisKey);
            }
        };
    }

    private void syncToRedis(String redisKey, List<ChatMessage> messages) {
        try {
            redisTemplate.opsForValue().set(redisKey, messages);
            redisTemplate.expire(redisKey, SESSION_EXPIRE_DAYS, TimeUnit.DAYS);
        } catch (Exception e) {
            System.err.println("Redis 同步失败: " + e.getMessage());
        }
    }

    public void clearMemory(String sessionId) {
        redisTemplate.delete(REDIS_KEY_PREFIX + sessionId);
    }

    public boolean hasMemory(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(REDIS_KEY_PREFIX + sessionId));
    }

    public java.util.Set<String> getAllSessionIds() {
        return redisTemplate.keys(REDIS_KEY_PREFIX + "*");
    }
}