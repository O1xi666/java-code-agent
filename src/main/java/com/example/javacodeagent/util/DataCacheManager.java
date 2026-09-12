package com.example.javacodeagent.util;

import com.example.javacodeagent.config.CachePolicy;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 多级缓存管理器
 *
 * L1: Caffeine 本地缓存（微秒级，带按策略 TTL 的过期检查）
 * L2: Redis 共享缓存（毫秒级，TTL 由 Redis 自身管理）
 *
 * 读取路径：L1(检查过期) → L2 → Supplier(真实获取) → 写回 L2 + L1
 */
@Service
public class DataCacheManager {

    private static final Logger log = LoggerFactory.getLogger(DataCacheManager.class);

    // L1: Caffeine，最多 500 条，1h 物理上限（实际按策略 TTL 由 CachedEntry 控制）
    private final Cache<String, CachedEntry> localCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(1, TimeUnit.HOURS)
            .recordStats()
            .build();

    // 精确过期时间表：key → 过期时间戳（毫秒）
    private final ConcurrentHashMap<String, Long> expiryMap = new ConcurrentHashMap<>();

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 多级缓存读取：L1(过期检查) → L2 → Supplier → 写回
     */
    @SuppressWarnings("unchecked")
    public <T> T getOrFetch(String key, Supplier<T> fetcher, CachePolicy policy) {
        // 1. L1: Caffeine + 策略级过期检查
        CachedEntry entry = localCache.getIfPresent(key);
        if (entry != null && !entry.isExpired()) {
            return (T) entry.value();
        }

        // 2. L2: Redis
        try {
            Object redisVal = redisTemplate.opsForValue().get(key);
            if (redisVal != null) {
                localCache.put(key, new CachedEntry(redisVal, policy));
                return (T) redisVal;
            }
        } catch (Exception e) {
            log.warn("Redis 读取失败（{}），跳过 L2: {}", key, e.getMessage());
        }

        // 3. 真实获取
        T result = fetcher.get();
        if (result == null) return null;

        // 4. 写回 L1 + L2
        localCache.put(key, new CachedEntry(result, policy));
        try {
            redisTemplate.opsForValue().set(key, result, policy.ttl(), policy.unit());
        } catch (Exception e) {
            log.warn("Redis 写入失败（{}）: {}", key, e.getMessage());
        }
        return result;
    }

    /**
     * 只读缓存，不触发真实获取
     */
    @SuppressWarnings("unchecked")
    public <T> T getIfPresent(String key) {
        CachedEntry entry = localCache.getIfPresent(key);
        if (entry != null && !entry.isExpired()) return (T) entry.value();
        try {
            return (T) redisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 手动写入（用于 LLM 分析结果等主动推缓存场景）
     */
    public void put(String key, Object value, CachePolicy policy) {
        localCache.put(key, new CachedEntry(value, policy));
        try {
            redisTemplate.opsForValue().set(key, value, policy.ttl(), policy.unit());
        } catch (Exception e) {
            log.warn("Redis 写入失败（{}）: {}", key, e.getMessage());
        }
    }

    /**
     * 手动淘汰（L1 + L2）
     */
    public void evict(String key) {
        localCache.invalidate(key);
        expiryMap.remove(key);
        try {
            redisTemplate.delete(key);
        } catch (Exception ignored) {}
    }

    /**
     * 只读暴露 L1 Caffeine 的命中统计，供诊断/评测使用；不改变任何缓存行为
     */
    public Map<String, Object> stats() {
        CacheStats cacheStats = localCache.stats();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("hitCount", cacheStats.hitCount());
        result.put("missCount", cacheStats.missCount());
        result.put("hitRate", cacheStats.hitRate());
        result.put("evictionCount", cacheStats.evictionCount());
        result.put("estimatedSize", localCache.estimatedSize());
        return result;
    }

    /**
     * 带按策略 TTL 精确过期的缓存条目
     */
    private record CachedEntry(Object value, long expiryTime) {
        CachedEntry(Object value, CachePolicy policy) {
            this(value, System.currentTimeMillis() + policy.unit().toMillis(policy.ttl()));
        }
        boolean isExpired() {
            return System.currentTimeMillis() >= expiryTime;
        }
    }
}
