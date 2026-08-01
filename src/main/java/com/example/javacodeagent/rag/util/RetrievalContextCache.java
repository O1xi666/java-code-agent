package com.example.javacodeagent.rag.util;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 工作记忆附属缓存
 *
 * <p>缓存每轮 RAG 检索到的原始上下文（行情、指标、财报片段等），
 * 带数据时效性标记。服务于当前会话的推理，避免实时数据过期误导分析。
 *
 * <p>TTL 策略（取数据 TTL 与会话级上限两者中较小值）：
 * <ul>
 *   <li>实时行情（STOCK_QUOTE）：1 小时</li>
 *   <li>K 线（STOCK_KLINE）：1 小时</li>
 *   <li>LLM 分析结果（LLM_ANALYSIS）：1 小时</li>
 *   <li>新闻（STOCK_NEWS）：1 天</li>
 *   <li>财报/基本面（FINANCIAL）：3 天</li>
 * </ul>
 *
 * <p>会话级上限 TTL：3 天（72 小时），任何数据类型不得越过此上限。
 */
@Component
public class RetrievalContextCache {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    private static final String KEY_PREFIX = "rag:cache:";
    private static final long SESSION_MAX_TTL_HOURS = 72;

    /**
     * 缓存数据类型，各类型拥有独立的 TTL。
     */
    public enum DataType {
        /** 实时行情：1 小时 */
        STOCK_QUOTE(1),
        /** K 线数据：1 小时 */
        STOCK_KLINE(1),
        /** LLM 分析结果：1 小时 */
        LLM_ANALYSIS(1),
        /** 新闻资讯：1 天 */
        STOCK_NEWS(24),
        /** 财报/基本面：3 天 */
        FINANCIAL(72);

        private final long ttlHours;

        DataType(long ttlHours) {
            this.ttlHours = ttlHours;
        }

        public long getTtlHours() {
            return ttlHours;
        }
    }

    /**
     * 写入缓存。实际 TTL 取 {@link DataType#getTtlHours()} 与会话级上限（72h）中的较小值。
     */
    public void put(String sessionId, DataType type, String content) {
        String key = buildKey(sessionId, type);
        long ttl = Math.min(type.getTtlHours(), SESSION_MAX_TTL_HOURS);
        try {
            redisTemplate.opsForValue().set(key, content, ttl, TimeUnit.HOURS);
        } catch (Exception e) {
            System.err.println("RetrievalContextCache 写入失败: " + e.getMessage());
        }
    }

    /**
     * 读取指定会话、指定类型的缓存内容。
     */
    @SuppressWarnings("unchecked")
    public String get(String sessionId, DataType type) {
        String key = buildKey(sessionId, type);
        try {
            Object val = redisTemplate.opsForValue().get(key);
            return val == null ? null : val.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取指定会话的全部缓存条目。
     */
    @SuppressWarnings("unchecked")
    public Map<DataType, String> getAllForSession(String sessionId) {
        Map<DataType, String> result = new HashMap<>();
        for (DataType type : DataType.values()) {
            String key = buildKey(sessionId, type);
            try {
                Object val = redisTemplate.opsForValue().get(key);
                if (val != null) {
                    result.put(type, val.toString());
                }
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    /**
     * 清除指定会话、指定类型缓存。
     */
    public void evict(String sessionId, DataType type) {
        redisTemplate.delete(buildKey(sessionId, type));
    }

    /**
     * 清除指定会话的全部缓存。
     */
    public void clearSession(String sessionId) {
        String pattern = KEY_PREFIX + sessionId + ":*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private String buildKey(String sessionId, DataType type) {
        return KEY_PREFIX + sessionId + ":" + type.name();
    }
}
