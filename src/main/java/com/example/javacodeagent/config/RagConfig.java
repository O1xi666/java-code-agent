package com.example.javacodeagent.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import com.example.javacodeagent.rag.RagPaths;
import com.example.javacodeagent.rag.service.HybridSearchService;
import com.example.javacodeagent.rag.service.LocalVectorService;
import com.example.javacodeagent.rag.util.BM25Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Value("${agent.tool.max-rounds:8}")
    private int maxToolRounds;

    @Value("${agent.llm.base-url:http://localhost:11434}")
    private String chatBaseUrl;

    @Value("${agent.llm.model:qwen3:8b}")
    private String chatModelName;

    @Value("${agent.embedding.base-url:http://localhost:11434}")
    private String embeddingBaseUrl;

    @Value("${agent.embedding.model:quentinz/bge-base-zh-v1.5:latest}")
    private String embeddingModelName;

    @Bean
    public ToolCallGuardModel ollamaChatModel() {
        OllamaChatModel raw = OllamaChatModel.builder()
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(0.0)
                .build();
        return new ToolCallGuardModel(raw, maxToolRounds);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .build();
    }

    /**
     * 上传文档库的 BM25 检索器，索引目录与 {@code DocumentService} 写入的目录保持一致。
     */
    @Bean
    public BM25Searcher bm25Searcher() {
        return new BM25Searcher(RagPaths.DOCS_BM25_INDEX);
    }

    @Bean
    public InMemoryEmbeddingStore<TextSegment> embeddingStore() {
        return LocalVectorService.loadPersistedStore();
    }

    /**
     * 上传文档库的自动召回通道：LangChain4j 在每次对话前调用它，把命中的文档片段拼进上下文。
     *
     * <p>这里刻意吞掉检索链路上的异常（例如 Ollama 未启动导致嵌入失败）：
     * 文档召回只是分析链路的增强项，不应让整个分析请求失败。</p>
     */
    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingModel embeddingModel,
            HybridSearchService hybridSearchService) {
        return query -> {
            String queryText = query == null ? "" : Objects.toString(query.text(), "").trim();
            if (queryText.isBlank()) return List.of();

            try {
                Response<Embedding> embeddingResponse = embeddingModel.embed(queryText);
                Embedding embedding = embeddingResponse == null ? null : embeddingResponse.content();
                if (embedding == null || embedding.vectorAsList() == null || embedding.vectorAsList().isEmpty()) {
                    return List.of();
                }

                List<HybridSearchService.HybridSearchResult> hits = hybridSearchService.search(
                        queryText, embedding.vectorAsList());
                if (hits == null || hits.isEmpty()) return List.of();

                List<Content> contents = new ArrayList<>(hits.size());
                for (int i = 0; i < hits.size(); i++) {
                    HybridSearchService.HybridSearchResult hit = hits.get(i);
                    String refId = "REF-" + (i + 1);
                    dev.langchain4j.data.document.Metadata metadata = new dev.langchain4j.data.document.Metadata()
                            .put("ref_id", refId)
                            .put("chunk_id", safe(hit.chunkId()))
                            .put("source", safe(hit.source()))
                            .put("token_count", hit.tokenCount())
                            .put("final_score", hit.finalScore());
                    String text = "[%s]%nchunk_id: %s%nsource: %s%ntext: %s"
                            .formatted(refId, safe(hit.chunkId()), safe(hit.source()), safe(hit.content()));
                    contents.add(Content.from(TextSegment.from(text, metadata)));
                }
                return contents;
            } catch (Exception e) {
                log.warn("文档库检索失败，本次跳过文档召回: {}", e.getMessage());
                return List.of();
            }
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
