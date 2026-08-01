package com.example.javacodeagent.rag.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 Ollama /api/rerank 的精排（rerank）服务。
 *
 * <p>在召回（向量 + BM25 融合）之后，对候选片段做二次精排，
 * 用 query 与每个候选内容计算相关度分数，再按分数取 TopK。
 *
 * <p>Ollama 需要支持 /api/rerank（较新版本）。若接口不可用
 * （例如当前 Ollama 0.32.5 未编译该路由），会自动降级为
 * 调用方传入的融合排序结果，不影响既有检索流程。
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.rerank.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${rag.rerank.model:dengcao/Qwen3-Reranker-0.6B:Q8_0}")
    private String modelName;

    @Value("${rag.rerank.timeout-seconds:30}")
    private int timeoutSeconds;

    /** 接口不可用时置为 false，避免每次请求都打日志。 */
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    /**
     * 对候选文档做精排。
     *
     * @param query      查询文本
     * @param documents  候选文档（与返回结果的 index 一一对应）
     * @param topN       最多返回条数
     * @return 按相关度降序排列的 {@link RerankResult}；接口不可用或失败时返回空列表
     */
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (!enabled.get() || query == null || query.isBlank()
                || documents == null || documents.isEmpty()) {
            return List.of();
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", modelName);
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", Math.max(1, topN));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/rerank"))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 404 || response.statusCode() == 501) {
                if (enabled.getAndSet(false)) {
                    log.warn("Ollama rerank API 不可用 (HTTP {}), 已禁用精排并回退到融合排序。"
                            + "请升级 Ollama 以支持 /api/rerank。", response.statusCode());
                }
                return List.of();
            }
            if (response.statusCode() != 200) {
                log.warn("Rerank API 返回异常状态: HTTP {}", response.statusCode());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return List.of();
            }

            List<RerankResult> out = new ArrayList<>(results.size());
            for (JsonNode node : results) {
                int index = node.path("index").asInt(-1);
                double score = node.path("relevance_score").asDouble(0.0);
                if (index >= 0) {
                    out.add(new RerankResult(index, score));
                }
            }
            out.sort(Comparator.comparingDouble(RerankResult::score).reversed());
            return out;
        } catch (Exception e) {
            log.warn("Rerank 调用失败，回退到融合排序: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 精排结果：index 指向候选列表中的原始下标，score 为相关度分数。
     */
    public record RerankResult(int index, double score) {
    }
}
