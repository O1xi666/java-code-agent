package com.example.javacodeagent.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RagConfig {

    // 1. 聊天对话模型
    @Bean
    public OllamaChatModel ollamaChatModel() {
        return OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3:8b")
                .temperature(0.0)
                .build();
    }

    // 2. 向量嵌入模型
    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("nomic-embed-text")
                .build();
    }

    // 3. 内存向量库
    @Bean
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    // 4. 修复版 RAG 检索器：增加调试日志和空值防御
    @Bean
    public ContentRetriever contentRetriever(EmbeddingModel embeddingModel, EmbeddingStore<TextSegment> store) {
        // --- 第一步：准备数据 ---
        List<String> rawKnowledge = List.of(
                "1. 空指针处理：调用对象方法前必须做非空判断，优先使用if (obj != null)",
                "2. Optional使用：简单场景禁止滥用Optional，禁止直接调用Optional.get()",
                "3. 代码规范：public类必须与文件名一致，类和方法使用驼峰命名",
                "4. 性能规范：循环内禁止创建对象、禁止用+拼接字符串"
        );

        List<TextSegment> segments = new ArrayList<>();
        for (String text : rawKnowledge) {
            // 强制去除首尾空格，防止空白字符导致问题
            String cleanText = text.trim();
            if (!cleanText.isEmpty()) {
                segments.add(TextSegment.from(cleanText));
            }
        }

        // --- 第二步：向量化并存入（带调试日志） ---
        System.out.println(">>> 正在初始化 RAG 知识库...");
        try {
            // 调用 embedAll，这会去请求 Ollama
            Response<List<Embedding>> embeddingResponse = embeddingModel.embedAll(segments);
            List<Embedding> embeddings = embeddingResponse.content();

            // 检查 Ollama 是否返回了空向量
            if (embeddings == null || embeddings.isEmpty()) {
                throw new RuntimeException("Ollama 嵌入模型返回了空结果！请检查 Ollama 服务是否正常。");
            }

            // 存入内存库
            for (int i = 0; i < segments.size(); i++) {
                store.add(embeddings.get(i), segments.get(i));
            }
            System.out.println(">>> RAG 知识库初始化成功，共入库 " + segments.size() + " 条数据。");
        } catch (Exception e) {
            System.err.println(">>> RAG 初始化失败: " + e.getMessage());
            e.printStackTrace();
            // 如果初始化失败，为了防止后续报错，我们可以选择不抛出异常，而是留空，
            // 或者在这里直接阻断启动。这里选择打印错误继续运行。
        }

        // --- 第三步：构建检索器 ---
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(store)
                .embeddingModel(embeddingModel)
                .maxResults(2)
                .minScore(0.0) // 设置为0，确保哪怕相关性低也能检索出来（调试用）
                .build();
    }
}