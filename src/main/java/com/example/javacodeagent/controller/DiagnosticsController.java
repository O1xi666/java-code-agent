package com.example.javacodeagent.controller;

import com.example.javacodeagent.service.FactCheckService;
import com.example.javacodeagent.service.StockMarketService;
import com.example.javacodeagent.util.DataCacheManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 诊断/评测接口
 *
 * 为简历中的两个量化指标提供可复现的测量入口：
 * 1. 事实一致性自校验：离线评测 harness 无需运行完整 LLM Agent 即可对回答打分
 * 2. 缓存基准：测量真实的多级缓存行情查询路径（L1 Caffeine + L2 Redis）
 * 3. 缓存统计：暴露 L1 Caffeine 的命中率等指标
 *
 * 全部为只读接口，不写入任何外部系统。
 */
@RestController
@RequestMapping("/api/diagnostics")
public class DiagnosticsController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsController.class);

    /** 基准测试默认标的：贵州茅台 */
    private static final String DEFAULT_SECID = "1.600519";
    private static final int MIN_ROUNDS = 1;
    private static final int MAX_ROUNDS = 200;

    private final DataCacheManager cacheManager;
    private final FactCheckService factCheckService;
    private final StockMarketService stockMarketService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DiagnosticsController(DataCacheManager cacheManager,
                                 FactCheckService factCheckService,
                                 StockMarketService stockMarketService) {
        this.cacheManager = cacheManager;
        this.factCheckService = factCheckService;
        this.stockMarketService = stockMarketService;
    }

    /**
     * L1 Caffeine 缓存命中统计
     */
    @GetMapping("/cache/stats")
    public Map<String, Object> cacheStats() {
        return cacheManager.stats();
    }

    /**
     * 事实一致性自校验（离线评测入口）
     *
     * 请求体：{question, knowledgeContext, toolObservations[], answer}
     * 返回：{dataAccurate, targetMatched, logicConsistent, passed, issues, fixInstructions}
     */
    @PostMapping("/fact-check")
    public ResponseEntity<Map<String, Object>> factCheck(@RequestBody(required = false) String body) {
        if (body == null || body.isBlank()) {
            return badRequest("请求体不能为空，需包含 question 与 answer 字段");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(body);
        } catch (Exception e) {
            return badRequest("请求体不是合法 JSON：" + e.getMessage());
        }
        String question = text(node, "question");
        String answer = text(node, "answer");
        if (question.isBlank()) {
            return badRequest("缺少必填字段 question");
        }
        if (answer.isBlank()) {
            return badRequest("缺少必填字段 answer");
        }
        String knowledgeContext = text(node, "knowledgeContext");
        List<String> toolObservations = toStringList(node.get("toolObservations"));

        FactCheckService.FactCheckReport report =
                factCheckService.check(question, knowledgeContext, toolObservations, answer);
        return ResponseEntity.ok(toMap(report));
    }

    /**
     * 真实多级缓存行情查询基准
     *
     * @param secid  股票代码，默认 1.600519（贵州茅台）
     * @param rounds 计时轮数，默认 20，限制在 1..200
     */
    @PostMapping("/cache/benchmark")
    public ResponseEntity<Map<String, Object>> cacheBenchmark(
            @RequestParam(value = "secid", defaultValue = DEFAULT_SECID) String secid,
            @RequestParam(value = "rounds", defaultValue = "20") int rounds) {
        int safeRounds = Math.max(MIN_ROUNDS, Math.min(MAX_ROUNDS, rounds));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("secid", secid);
        result.put("rounds", safeRounds);
        result.put("sourceOfTruth",
                "StockMarketService.getQuote 真实多级缓存路径：冷路径回源新浪行情，热路径由 L1 Caffeine(30s TTL) + L2 Redis 提供");
        try {
            // 1. 预热：首次调用在无缓存时走冷路径（回源网络）
            long coldStart = System.nanoTime();
            stockMarketService.getQuote(secid);
            double coldPathMs = toMillis(System.nanoTime() - coldStart);

            // 2. 计时 rounds 次热路径调用
            long[] warmNanos = new long[safeRounds];
            for (int i = 0; i < safeRounds; i++) {
                long start = System.nanoTime();
                stockMarketService.getQuote(secid);
                warmNanos[i] = System.nanoTime() - start;
            }

            double totalMs = 0;
            for (long ns : warmNanos) {
                totalMs += toMillis(ns);
            }
            double warmAvgMs = totalMs / safeRounds;

            long[] sorted = Arrays.copyOf(warmNanos, warmNanos.length);
            Arrays.sort(sorted);
            double warmP50Ms = toMillis(sorted[sorted.length / 2]);
            double warmMaxMs = toMillis(sorted[sorted.length - 1]);

            Object hitRate = cacheManager.stats().get("hitRate");

            result.put("success", true);
            result.put("coldPathMs", round3(coldPathMs));
            result.put("warmAvgMs", round3(warmAvgMs));
            result.put("warmP50Ms", round3(warmP50Ms));
            result.put("warmMaxMs", round3(warmMaxMs));
            result.put("cacheHitRate", hitRate);
            result.put("cacheEffective", warmAvgMs < coldPathMs);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.warn("缓存基准执行失败: {}", e.getMessage());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("success", false);
            error.put("secid", secid);
            error.put("rounds", safeRounds);
            error.put("error", "缓存基准执行失败：" + e.getMessage());
            error.put("hint", "请确认网络可访问行情源，且 secid 格式为 1.600519（沪市）或 0.300750（深市）");
            return ResponseEntity.status(500).body(error);
        }
    }

    private static Map<String, Object> toMap(FactCheckService.FactCheckReport report) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dataAccurate", report.dataAccurate());
        result.put("targetMatched", report.targetMatched());
        result.put("logicConsistent", report.logicConsistent());
        result.put("passed", report.passed());
        result.put("issues", report.issues());
        result.put("fixInstructions", report.fixInstructions());
        return result;
    }

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("success", false);
        error.put("error", message);
        return ResponseEntity.badRequest().body(error);
    }

    private static List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> result.add(item == null || item.isNull() ? "" : item.asText("")));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static double toMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
