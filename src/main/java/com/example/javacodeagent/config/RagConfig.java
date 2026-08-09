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
import com.example.javacodeagent.rag.service.HybridSearchService;
import com.example.javacodeagent.rag.util.BM25Searcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    private static final String VECTOR_STORE_FILE = "rag-vector-index/vector-store.json";

    @Value("${agent.tool.max-rounds:8}")
    private int maxToolRounds;

    @Bean
    public ToolCallGuardModel ollamaChatModel() {
        OllamaChatModel raw = OllamaChatModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("qwen3:8b")
                .temperature(0.0)
                .build();
        return new ToolCallGuardModel(raw, maxToolRounds);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("nomic-embed-text")
                .modelName("quentinz/bge-base-zh-v1.5:latest")
                .build();
    }

    @Bean
    public BM25Searcher bm25Searcher() {
        return new BM25Searcher(Path.of("rag-bm25-index"));
    }

    @Bean
    public InMemoryEmbeddingStore<TextSegment> embeddingStore() {
        Path storePath = Path.of(VECTOR_STORE_FILE);
        if (Files.exists(storePath)) {
            try {
                String json = Files.readString(storePath);
                return InMemoryEmbeddingStore.fromJson(json);
            } catch (IOException e) {
                log.warn("Failed loading vector store: {}", e.getMessage());
            }
        }
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingModel embeddingModel,
            HybridSearchService hybridSearchService) {
        return query -> {
            String queryText = query == null ? "" : Objects.toString(query.text(), "").trim();
            if (queryText.isBlank()) return List.of();

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
        };
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
