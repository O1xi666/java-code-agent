package com.example.javacodeagent.rag.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地向量检索服务 — 基于 LangChain4j InMemoryEmbeddingStore
 *
 * 技术亮点（面试关注点）：
 * 1. 替代了原来的 MilvusVectorService，改为纯内存实现，零外部依赖
 * 2. 基于余弦相似度（cosine similarity）计算向量距离
 * 3. 支持持久化（toJson / fromJson），重启时可从磁盘恢复
 * 4. 与 BM25 形成向量 + 关键词的混合检索（HybridSearchService 使用）
 *
 * 设计思路：
 * - 为什么不用 Milvus？因为本地场景（几十到几百份研报）内存绰绰有余，
 *   引入 Milvus 增加了运维成本但没有实际收益
 * - InMemoryEmbeddingStore 由 LangChain4j 内置，是轻量级向量存储的最佳选择
 * - 向量维度固定为 768（nomic-embed-text 模型输出维度）
 *
 * 面试可能会问：
 * Q: 为什么选择 768 维？
 * A: 由 embedding 模型决定，nomic-embed-text 输出 768 维向量，
 *    这个维度在准确率和计算成本之间取得了较好的平衡
 *
 * Q: 余弦相似度的公式是什么？
 * A: cos(A,B) = (A·B) / (|A| * |B|)，范围 [-1, 1]，
 *    越接近 1 表示越相似
 */
@Service
public class LocalVectorService {

    private static final Logger log = LoggerFactory.getLogger(LocalVectorService.class);

    public static final int TOP_K = 20;
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_TOKEN_COUNT = "token_count";
    public static final int VECTOR_DIMENSION = 768;

    private final EmbeddingStore<TextSegment> embeddingStore;

    public LocalVectorService(EmbeddingStore<TextSegment> embeddingStore) {
        this.embeddingStore = embeddingStore;
    }

    /**
     * 向量检索 Top K 个相似 chunk
     *
     * @param queryVector 查询向量（768 维）
     * @return 相似 chunk 列表
     */
    public List<SimilarChunk> searchTopChunks(List<Float> queryVector) {
        if (queryVector == null || queryVector.size() != VECTOR_DIMENSION) {
            log.warn("无效的查询向量，维度: {}", queryVector == null ? 0 : queryVector.size());
            return List.of();
        }

        Embedding queryEmbedding = Embedding.from(queryVector);
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.findRelevant(queryEmbedding, TOP_K);

        List<SimilarChunk> results = new ArrayList<>(matches.size());
        for (EmbeddingMatch<TextSegment> match : matches) {
            TextSegment segment = match.embedded();
            if (segment == null) continue;

            results.add(new SimilarChunk(
                    segment.metadata().getString(FIELD_CHUNK_ID),
                    segment.text(),
                    segment.metadata().getString(FIELD_SOURCE),
                    segment.metadata().getInteger(FIELD_TOKEN_COUNT),
                    match.score().floatValue()
            ));
        }

        return results;
    }

    /**
     * 批量插入向量记录
     *
     * @param records 待插入记录列表
     */
    public void insertBatch(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        List<TextSegment> segments = new ArrayList<>(records.size());
        List<Embedding> embeddings = new ArrayList<>(records.size());

        for (VectorRecord record : records) {
            TextSegment segment = TextSegment.from(record.content());
            segment.metadata().put(FIELD_CHUNK_ID, record.chunkId());
            segment.metadata().put(FIELD_SOURCE, record.source());
            segment.metadata().put(FIELD_TOKEN_COUNT, record.tokenCount());

            Embedding embedding = Embedding.from(record.vector());

            segments.add(segment);
            embeddings.add(embedding);
        }

        embeddingStore.addAll(embeddings, segments);
        log.info("批量插入完成，共 {} 条记录", records.size());
    }

    /**
     * 入库存记录模型
     */
    public record VectorRecord(
            String chunkId,
            String content,
            String source,
            int tokenCount,
            List<Float> vector
    ) {}

    /**
     * 检索结果模型
     */
    public record SimilarChunk(
            String chunkId,
            String content,
            String source,
            int tokenCount,
            float score
    ) {}
}
