package com.example.javacodeagent.rag.service;

import com.example.javacodeagent.rag.util.BM25Searcher;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * 混合检索服务（向量检索 + BM25 检索）。
 * <p>
 * 核心策略：
 * <ul>
 *     <li>并行调用 Milvus 向量检索 与 BM25 检索</li>
 *     <li>分数加权融合：向量 70%，BM25 30%</li>
 *     <li>按 chunk_id 去重后排序，返回 Top5</li>
 * </ul>
 */
@Service
public class HybridSearchService {

    /**
     * 向量检索权重（70%）。
     */
    private static final double VECTOR_WEIGHT = 0.70d;

    /**
     * BM25 检索权重（30%）。
     */
    private static final double BM25_WEIGHT = 0.30d;

    /**
     * 最终返回结果条数。
     */
    private static final int FINAL_TOP_K = 5;

    private final MilvusVectorService milvusVectorService;
    private final BM25Searcher bm25Searcher;

    public HybridSearchService(MilvusVectorService milvusVectorService, BM25Searcher bm25Searcher) {
        this.milvusVectorService = milvusVectorService;
        this.bm25Searcher = bm25Searcher;
    }

    /**
     * 执行混合检索。
     *
     * @param queryText   用于 BM25 的查询文本
     * @param queryVector 用于 Milvus 的查询向量（1024 维）
     * @return 去重+融合后 Top5 结果，包含完整溯源元数据
     */
    public List<HybridSearchResult> search(String queryText, List<Float> queryVector) {
        // 使用 Java 21 虚拟线程并发执行两路检索，降低端到端时延。
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<MilvusVectorService.SimilarChunk>> vectorFuture =
                    CompletableFuture.supplyAsync(() -> milvusVectorService.searchTopChunks(queryVector), executor);
            CompletableFuture<List<BM25Searcher.Bm25Result>> bm25Future =
                    CompletableFuture.supplyAsync(() -> bm25Searcher.searchTopChunks(queryText), executor);

            List<MilvusVectorService.SimilarChunk> vectorResults = vectorFuture.join();
            List<BM25Searcher.Bm25Result> bm25Results = bm25Future.join();

            return fuseResults(vectorResults, bm25Results);
        }
    }

    /**
     * 融合逻辑说明：
     * <ol>
     *     <li>先基于排名计算归一化分（避免向量分与 BM25 分量纲不同）</li>
     *     <li>按 0.7 / 0.3 加权求和</li>
     *     <li>以 chunk_id 去重并聚合元数据</li>
     *     <li>按融合分降序取 Top5</li>
     * </ol>
     */
    private List<HybridSearchResult> fuseResults(
            List<MilvusVectorService.SimilarChunk> vectorResults,
            List<BM25Searcher.Bm25Result> bm25Results
    ) {
        Map<String, FusionAccumulator> merged = new HashMap<>();

        applyVectorScores(vectorResults, merged);
        applyBm25Scores(bm25Results, merged);

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::finalScore).reversed())
                .limit(FINAL_TOP_K)
                .map(FusionAccumulator::toResult)
                .toList();
    }

    /**
     * 写入向量侧得分。
     */
    private void applyVectorScores(
            List<MilvusVectorService.SimilarChunk> vectorResults,
            Map<String, FusionAccumulator> merged
    ) {
        if (vectorResults == null || vectorResults.isEmpty()) {
            return;
        }

        int total = vectorResults.size();
        for (int i = 0; i < total; i++) {
            MilvusVectorService.SimilarChunk hit = vectorResults.get(i);
            String chunkId = safeChunkId(hit.chunkId());

            // 排名归一化分：rank=1 -> 1.0, rank=total -> 1/total
            double rankNormalized = (double) (total - i) / total;
            double weighted = rankNormalized * VECTOR_WEIGHT;

            FusionAccumulator acc = merged.computeIfAbsent(chunkId, FusionAccumulator::new);
            acc.content = preferNonBlank(acc.content, hit.content());
            acc.source = preferNonBlank(acc.source, hit.source());
            acc.tokenCount = Math.max(acc.tokenCount, hit.tokenCount());
            acc.vectorRawScore = hit.score();
            acc.vectorRank = i + 1;
            acc.vectorWeightedScore = weighted;
            acc.finalScore += weighted;
            acc.hitByVector = true;
        }
    }

    /**
     * 写入 BM25 侧得分。
     */
    private void applyBm25Scores(
            List<BM25Searcher.Bm25Result> bm25Results,
            Map<String, FusionAccumulator> merged
    ) {
        if (bm25Results == null || bm25Results.isEmpty()) {
            return;
        }

        int total = bm25Results.size();
        for (int i = 0; i < total; i++) {
            BM25Searcher.Bm25Result hit = bm25Results.get(i);
            String chunkId = safeChunkId(hit.chunkId());

            double rankNormalized = (double) (total - i) / total;
            double weighted = rankNormalized * BM25_WEIGHT;

            FusionAccumulator acc = merged.computeIfAbsent(chunkId, FusionAccumulator::new);
            acc.content = preferNonBlank(acc.content, hit.content());
            // BM25 侧没有 source/tokenCount，保留已有值（若无则维持默认）。
            acc.bm25RawScore = hit.score();
            acc.bm25Rank = i + 1;
            acc.bm25WeightedScore = weighted;
            acc.finalScore += weighted;
            acc.hitByBm25 = true;
        }
    }

    private static String safeChunkId(String chunkId) {
        if (chunkId == null || chunkId.isBlank()) {
            return "unknown-chunk-" + System.nanoTime();
        }
        return chunkId;
    }

    private static String preferNonBlank(String current, String candidate) {
        if (current != null && !current.isBlank()) {
            return current;
        }
        return candidate == null ? "" : candidate;
    }

    /**
     * 最终对外返回模型（包含溯源元数据）。
     */
    public record HybridSearchResult(
            String chunkId,
            String content,
            String source,
            int tokenCount,
            double finalScore,
            float vectorRawScore,
            float bm25RawScore,
            double vectorWeightedScore,
            double bm25WeightedScore,
            int vectorRank,
            int bm25Rank,
            boolean hitByVector,
            boolean hitByBm25
    ) {
    }

    /**
     * 融合过程中使用的可变聚合对象。
     */
    private static final class FusionAccumulator {
        private final String chunkId;
        private String content = "";
        private String source = "";
        private int tokenCount = 0;
        private double finalScore = 0.0d;
        private float vectorRawScore = 0.0f;
        private float bm25RawScore = 0.0f;
        private double vectorWeightedScore = 0.0d;
        private double bm25WeightedScore = 0.0d;
        private int vectorRank = 0;
        private int bm25Rank = 0;
        private boolean hitByVector = false;
        private boolean hitByBm25 = false;

        private FusionAccumulator(String chunkId) {
            this.chunkId = chunkId;
        }

        private double finalScore() {
            return finalScore;
        }

        private HybridSearchResult toResult() {
            return new HybridSearchResult(
                    chunkId,
                    content,
                    source,
                    tokenCount,
                    finalScore,
                    vectorRawScore,
                    bm25RawScore,
                    vectorWeightedScore,
                    bm25WeightedScore,
                    vectorRank,
                    bm25Rank,
                    hitByVector,
                    hitByBm25
            );
        }
    }
}
