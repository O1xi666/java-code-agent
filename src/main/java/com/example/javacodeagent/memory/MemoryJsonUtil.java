package com.example.javacodeagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 记忆层内部使用的 JSON 工具。
 *
 * <p>记忆数据统一以 JSON 字符串形式落到 Redis，而不是直接交给 RedisTemplate 的
 * Jackson 序列化器：容器里的序列化器开启了 {@code activateDefaultTyping(NON_FINAL)}，
 * 对 record / final 类不会写入类型信息，反序列化会退化成 LinkedHashMap。
 * 自己控制 JSON 编解码可以规避这个坑，同时让 Redis 里的内容可读、可人工排查。
 */
public final class MemoryJsonUtil {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private MemoryJsonUtil() {
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    public static <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank() || "null".equals(json)) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }

    public static <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank() || "null".equals(json)) return null;
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            return null;
        }
    }
}
