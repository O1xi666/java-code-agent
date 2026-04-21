package com.example.javacodeagent.rag.config;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 客户端基础配置。
 * <p>
 * 当前默认对接本地 Milvus（localhost:19530），
 * 后续可通过 application.yml 覆盖 rag.milvus.* 参数。
 */
@Configuration
public class MilvusConfig {

    @Value("${rag.milvus.host:localhost}")
    private String host;

    @Value("${rag.milvus.port:19530}")
    private int port;

    @Value("${rag.milvus.token:}")
    private String token;

    /**
     * 创建 Milvus Java SDK v2 客户端 Bean，供 RAG 服务层注入使用。
     */
    @Bean
    public MilvusClientV2 milvusClientV2() {
        ConnectConfig.ConnectConfigBuilder builder = ConnectConfig.builder()
                .uri("http://" + host + ":" + port);

        // 本地无鉴权时 token 为空；有鉴权时可在配置中注入。
        if (token != null && !token.isBlank()) {
            builder.token(token);
        }

        return new MilvusClientV2(builder.build());
    }
}
