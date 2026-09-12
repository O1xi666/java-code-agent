package com.example.javacodeagent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记忆存储底座：Redis 为主，进程内 Map 为兜底。
 *
 * <p>为什么需要兜底：会话记忆、用户画像、历史结论都属于增强能力，
 * 不应该因为 Redis 不可用就让整条分析链路失败。Redis 写失败时降级到进程内存储，
 * 功能语义保持一致（只是多实例之间不再共享），并打一次 WARN 日志便于排查。
 */
@Component
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    /** Redis 连续失败后置为 false，避免每条消息都打一次异常日志 */
    private final AtomicBoolean redisHealthy = new AtomicBoolean(true);
    private final Map<String, String> localFallback = new ConcurrentHashMap<>();

    private final RedisTemplate<String, Object> redisTemplate;

    public MemoryStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String get(String key) {
        if (redisTemplate != null && redisHealthy.get()) {
            try {
                Object value = redisTemplate.opsForValue().get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            } catch (Exception e) {
                markRedisUnavailable(key, e);
            }
        }
        return localFallback.get(key);
    }

    public void put(String key, String json, Duration ttl) {
        if (json == null) return;
        localFallback.put(key, json);
        if (redisTemplate != null && redisHealthy.get()) {
            try {
                redisTemplate.opsForValue().set(key, json, ttl);
            } catch (Exception e) {
                markRedisUnavailable(key, e);
            }
        }
    }

    public void remove(String key) {
        localFallback.remove(key);
        if (redisTemplate != null) {
            try {
                redisTemplate.delete(key);
            } catch (Exception ignored) {
                // 删除失败不影响主流程
            }
        }
    }

    /** 供诊断接口使用：当前记忆存储是否仍走 Redis */
    public boolean isRedisHealthy() {
        return redisTemplate != null && redisHealthy.get();
    }

    private void markRedisUnavailable(String key, Exception e) {
        if (redisHealthy.compareAndSet(true, false)) {
            log.warn("Redis 不可用，记忆层降级为进程内存储（key={}）: {}", key, e.getMessage());
        }
    }
}
