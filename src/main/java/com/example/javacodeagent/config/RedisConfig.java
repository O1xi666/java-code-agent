package com.example.javacodeagent.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置类
 * 作用：配置 Redis 连接、序列化方式，确保数据正确存储和读取
 */
@Configuration
public class RedisConfig {

    /**
     * 配置 RedisTemplate
     * 关键点：
     * 1. Key 使用 String 序列化（方便查看和管理）
     * 2. Value 使用 JSON 序列化（支持复杂对象存储）
     * 3. 启用类型信息（反序列化时能正确还原对象类型）
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        // 创建 RedisTemplate 实例
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // ✅ 配置 JSON 序列化器（用于 Value）
        Jackson2JsonRedisSerializer<Object> jacksonSerializer = createJacksonSerializer();

        // ✅ 配置 String 序列化器（用于 Key）
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // 设置 Key 的序列化方式 → String
        template.setKeySerializer(stringSerializer);
        // 设置 Value 的序列化方式 → JSON
        template.setValueSerializer(jacksonSerializer);
        // 设置 Hash Key 的序列化方式 → String
        template.setHashKeySerializer(stringSerializer);
        // 设置 Hash Value 的序列化方式 → JSON
        template.setHashValueSerializer(jacksonSerializer);

        // 初始化配置
        template.afterPropertiesSet();
        return template;
    }

    /**
     * 创建 Jackson JSON 序列化器
     * 作用：将 Java 对象序列化为 JSON 存储到 Redis，读取时再反序列化回对象
     */
    private Jackson2JsonRedisSerializer<Object> createJacksonSerializer() {
        // 创建 ObjectMapper（JSON 处理核心类）
        ObjectMapper objectMapper = new ObjectMapper();

        // 设置可见性：允许访问所有字段（包括私有字段）
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);

        // ✅ 激活默认类型信息（关键！）
        // 这样反序列化时才能知道要把 JSON 转成什么类型的对象
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL
        );

        return new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
    }
}

