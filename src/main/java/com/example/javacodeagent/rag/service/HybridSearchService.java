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
 * 混合检索服务（向量检索 + BM25 检索）
 *
 * 技术亮点（面试关注点）：
 * 1. 并行调用 LocalVectorService（向量）和 BM25Searcher（关键词）
 * 2. 使用虚拟线程（Java 21 VirtualThread）并行执行，降低响应延迟
 * 3. 融合权重 7:3，兼顾语义相似度和关键词匹配
 * 4. 基于 chunk_id 去重，保留更丰富的元数据
 * 5. 返回 Top5 融合结果
 */
@Service
public class HybridSearchService {

    private static final double VECTOR_WEIGHT = 0.70d;
    private static final double BM25_WEIGHT = 0.30d;
    private static final int FINAL_TOP_K = 5;
    /** 融合后进入精排的候选池大小 */
    private static final int FUSION_CANDIDATES = 30;

    private final LocalVectorService localVectorService;
    private final BM25Searcher bm25Searcher;
    private final RerankService rerankService;

    public HybridSearchService(
            LocalVectorService localVectorService,
            BM25Searcher bm25Searcher,
            RerankService rerankService) {
        this.localVectorService = localVectorService;
        this.bm25Searcher = bm25Searcher;
        this.rerankService = rerankService;
    }

    /**
     * 执行混合检索
     *
     * @param queryText   用于 BM25 的查询文本
     * @param queryVector 用于向量检索的查询向量（768 维）
     * @return 去重+融合后 Top5 结果
     */
    public List<HybridSearchResult> search(String queryText, List<Float> queryVector) {
        try (ExecutorService executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<List<LocalVectorService.SimilarChunk>> vectorFuture =
                    CompletableFuture.supplyAsync(() -> localVectorService.searchTopChunks(queryVector), executor);
            CompletableFuture<List<BM25Searcher.Bm25Result>> bm25Future =
                    CompletableFuture.supplyAsync(() -> bm25Searcher.searchTopChunks(queryText), executor);

            List<LocalVectorService.SimilarChunk> vectorResults = vectorFuture.join();
            List<BM25Searcher.Bm25Result> bm25Results = bm25Future.join();

            List<HybridSearchResult> candidates = fuseResults(vectorResults, bm25Results, FUSION_CANDIDATES);
            return rerankResults(candidates, queryText, FINAL_TOP_K);
        }
    }

    /**
     * 融合逻辑：
     * 1. 基于排名计算归一化分
     * 2. 按 0.7 / 0.3 加权求和
     * 3. 基于 chunk_id 去重并聚合元数据
     * 4. 按融合分降序取 Top5
     */
    private List<HybridSearchResult> fuseResults(
            List<LocalVectorService.SimilarChunk> vectorResults,
            List<BM25Searcher.Bm25Result> bm25Results,
            int candidateLimit
    ) {
        Map<String, FusionAccumulator> merged = new HashMap<>();
        applyVectorScores(vectorResults, merged);
        applyBm25Scores(bm25Results, merged);

        return merged.values().stream()
                .sorted(Comparator.comparingDouble(FusionAccumulator::finalScore).reversed())
                .limit(candidateLimit)
                .map(FusionAccumulator::toResult)
                .toList();
    }

    /**
     * 对融合后的候选执行精排并截取最终 TopK。
     * 精排失败或不可用时，按融合排序取前 topK。
     */
    private List<HybridSearchResult> rerankResults(
            List<HybridSearchResult> candidates, String queryText, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> docs = candidates.stream()
                .map(HybridSearchResult::content)
                .toList();
        List<RerankService.RerankResult> results = rerankService.rerank(queryText, docs, topK);

        if (results.isEmpty()) {
            return candidates.size() <= topK ? candidates : candidates.subList(0, topK);
        }

        List<HybridSearchResult> out = new ArrayList<>(results.size());
        for (RerankService.RerankResult rr : results) {
            int idx = rr.index();
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            HybridSearchResult c = candidates.get(idx);
            out.add(new HybridSearchResult(
                    c.chunkId(), c.content(), c.source(), c.tokenCount(),
                    rr.score(),
                    c.vectorRawScore(), c.bm25RawScore(),
                    c.vectorWeightedScore(), c.bm25WeightedScore(),
                    c.vectorRank(), c.bm25Rank(), c.hitByVector(), c.hitByBm25()));
        }
        return out;
    }

    private void applyVectorScores(
            List<LocalVectorService.SimilarChunk> vectorResults,
            Map<String, FusionAccumulator> merged
    ) {
        if (vectorResults == null || vectorResults.isEmpty()) return;

        int total = vectorResults.size();
        for (int i = 0; i < total; i++) {
            LocalVectorService.SimilarChunk hit = vectorResults.get(i);
            String chunkId = safeChunkId(hit.chunkId());

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

    private void applyBm25Scores(
            List<BM25Searcher.Bm25Result> bm25Results,
            Map<String, FusionAccumulator> merged
    ) {
        if (bm25Results == null || bm25Results.isEmpty()) return;

        int total = bm25Results.size();
        for (int i = 0; i < total; i++) {
            BM25Searcher.Bm25Result hit = bm25Results.get(i);
            String chunkId = safeChunkId(hit.chunkId());

            double rankNormalized = (double) (total - i) / total;
            double weighted = rankNormalized * BM25_WEIGHT;

            FusionAccumulator acc = merged.computeIfAbsent(chunkId, FusionAccumulator::new);
            acc.content = preferNonBlank(acc.content, hit.content());
            acc.bm25RawScore = hit.score();
            acc.bm25Rank = i + 1;
            acc.bm25WeightedScore = weighted;
            acc.finalScore += weighted;
            acc.hitByBm25 = true;
        }
    }

    private static String safeChunkId(String chunkId) {
        return (chunkId == null || chunkId.isBlank()) ? "unknown-chunk-" + System.nanoTime() : chunkId;
    }

    private static String preferNonBlank(String current, String candidate) {
        return (current != null && !current.isBlank()) ? current : (candidate == null ? "" : candidate);
    }

    public record HybridSearchResult(
            String chunkId, String content, String source, int tokenCount,
            double finalScore, float vectorRawScore, float bm25RawScore,
            double vectorWeightedScore, double bm25WeightedScore,
            int vectorRank, int bm25Rank, boolean hitByVector, boolean hitByBm25
    ) {}

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

        private FusionAccumulator(String chunkId) { this.chunkId = chunkId; }
        private double finalScore() { return finalScore; }

        private HybridSearchResult toResult() {
            return new HybridSearchResult(chunkId, content, source, tokenCount,
                    finalScore, vectorRawScore, bm25RawScore,
                    vectorWeightedScore, bm25WeightedScore,
                    vectorRank, bm25Rank, hitByVector, hitByBm25);
        }
    }
}
