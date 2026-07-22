package com.example.javacodeagent.controller;

import com.example.javacodeagent.rag.model.KnowledgeEntry;
import com.example.javacodeagent.rag.service.KnowledgeBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投研知识库 REST API
 *
 * <p>管理股票相关的投研知识条目（行情数据、算法逻辑、策略片段等）
 * 支持CSV批量导入、单条插入、语义检索、查看统计、清空重建。
 *
 * <p>API 汇总：
 * <pre>
 * POST   /api/knowledge/insert        - 插入单条知识
 * POST   /api/knowledge/import-csv    - 导入CSV文件
 * GET    /api/knowledge/retrieve      - 检索知识
 * GET    /api/knowledge/stocks        - 列出有知识条目的股票
 * GET    /api/knowledge/stats         - 知识库统计
 * DELETE /api/knowledge/clear         - 清空知识库
 * POST   /api/knowledge/rebuild       - 重建索引
 * </pre>
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);

    private final KnowledgeBaseService knowledgeBaseService;

    public KnowledgeController(KnowledgeBaseService knowledgeBaseService) {
        this.knowledgeBaseService = knowledgeBaseService;
    }

    // ═════════════════════════════════════════════════════════════
    // 插入
    // ═════════════════════════════════════════════════════════════

    /**
     * 插入单条知识条目。
     *
     * @param body JSON: {"stockCode":"1.600519","stockName":"贵州茅台","content":"...","category":"market_data","source":"财报","tags":"茅台,批价"}
     * @return 创建的条目
     */
    @PostMapping("/insert")
    public ResponseEntity<Map<String, Object>> insert(@RequestBody Map<String, String> body) {
        String stockCode = body.getOrDefault("stockCode", "").trim();
        String stockName = body.getOrDefault("stockName", "").trim();
        String content = body.getOrDefault("content", "").trim();
        String category = body.getOrDefault("category", "general").trim();
        String source = body.getOrDefault("source", "").trim();
        String tags = body.getOrDefault("tags", "").trim();

        if (stockCode.isBlank() || content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "stockCode 和 content 不能为空"));
        }

        KnowledgeEntry entry = knowledgeBaseService.insert(stockCode, stockName, content, category, source, tags);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("entryId", entry.getId());
        result.put("stockCode", entry.getStockCode());
        result.put("stockName", entry.getStockName());
        result.put("category", entry.getCategory());
        result.put("contentLen", entry.getContent().length());
        result.put("source", entry.getSource());
        result.put("tags", entry.getTags());
        return ResponseEntity.ok(result);
    }

    // ═════════════════════════════════════════════════════════════
    // CSV导入
    // ═════════════════════════════════════════════════════════════

    /**
     * 导入CSV文件（字段: stock_code,stock_name,category,content,tags,source）。
     *
     * @param file 上传的CSV文件
     * @return 导入结果统计
     */
    @PostMapping("/import-csv")
    public ResponseEntity<Map<String, Object>> importCsv(@RequestParam("file") MultipartFile file) {
        log.info("CSV导入请求: name={}, size={}", file.getOriginalFilename(), file.getSize());

        try {
            KnowledgeBaseService.CsvImportResult result = knowledgeBaseService.importCsv(file);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("importedCount", result.importedCount());
            resp.put("totalRows", result.totalRows());
            resp.put("skippedRows", result.skippedRows());
            resp.put("errors", result.errors());
            resp.put("summary", result.summary());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.error("CSV导入失败", e);
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "CSV导入失败: " + e.getMessage()));
        }
    }

    // ═════════════════════════════════════════════════════════════
    // 检索
    // ═════════════════════════════════════════════════════════════

    /**
     * 检索知识条目。
     *
     * @param query     查询文本（必填）
     * @param stockCode 可选，限定股票代码
     * @param topK      可选，返回条数（默认5）
     * @return 按相关性降序排列的知识片段列表
     */
    @GetMapping("/retrieve")
    public ResponseEntity<Map<String, Object>> retrieve(
            @RequestParam("query") String query,
            @RequestParam(value = "stockCode", required = false, defaultValue = "") String stockCode,
            @RequestParam(value = "topK", required = false, defaultValue = "5") int topK
    ) {
        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "query不能为空"));
        }

        String filterCode = stockCode != null && !stockCode.isBlank() ? stockCode.trim() : null;
        List<KnowledgeBaseService.RetrievedKnowledge> results =
                knowledgeBaseService.retrieve(query, filterCode, topK);

        List<Map<String, Object>> items = results.stream().map(rk -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("entryId", rk.getEntryId());
            item.put("stockCode", rk.getStockCode());
            item.put("stockName", rk.getStockName());
            item.put("content", rk.getContent());
            item.put("category", rk.getCategory());
            item.put("source", rk.getSource());
            item.put("tags", rk.getTags());
            item.put("score", rk.getScore());
            return item;
        }).toList();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("query", query);
        resp.put("stockCode", filterCode);
        resp.put("count", items.size());
        resp.put("results", items);

        // 同时返回可直接喂入 LLM 的上下文文本
        if (!items.isEmpty()) {
            String context = knowledgeBaseService.buildKnowledgeContext(query, filterCode);
            resp.put("context", context);
        }

        return ResponseEntity.ok(resp);
    }

    // ═════════════════════════════════════════════════════════════
    // 统计 & 管理
    // ═════════════════════════════════════════════════════════════

    /**
     * 列出知识库中所有有知识条目的股票代码。
     */
    @GetMapping("/stocks")
    public ResponseEntity<Map<String, Object>> listStocks() {
        Set<String> codes = knowledgeBaseService.getStockCodes();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("count", codes.size());
        resp.put("stocks", codes);
        return ResponseEntity.ok(resp);
    }


    // ═════════════════════════════════════════════════════════════
    // 通用规则管理
    // ═════════════════════════════════════════════════════════════

    /**
     * 添加通用规则（每次分析自动附带）。
     */
    @PostMapping("/general-rule")
    public ResponseEntity<Map<String, Object>> addGeneralRule(@RequestBody Map<String, String> body) {
        String content = body.getOrDefault("content", "").trim();
        String source = body.getOrDefault("source", "").trim();
        String tags = body.getOrDefault("tags", "").trim();
        if (content.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "content 不能为空"));
        }
        KnowledgeEntry entry = knowledgeBaseService.insertGeneralRule(content, source, tags);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("entryId", entry.getId());
        resp.put("content", entry.getContent().length() > 100 ? entry.getContent().substring(0, 100) + "..." : entry.getContent());
        resp.put("createdAt", entry.getCreatedAt().toString());
        return ResponseEntity.ok(resp);
    }

    /**
     * 获取所有通用规则。
     */
    @GetMapping("/general-rules")
    public ResponseEntity<Map<String, Object>> listGeneralRules() {
        List<KnowledgeEntry> rules = knowledgeBaseService.getGeneralRules();
        List<Map<String, Object>> items = rules.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryId", r.getId());
            m.put("content", r.getContent());
            m.put("source", r.getSource());
            m.put("tags", r.getTags());
            m.put("createdAt", r.getCreatedAt().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("success", true, "count", items.size(), "rules", items));
    }

    /**
     * 删除指定通用规则。
     */
    @DeleteMapping("/general-rule/{entryId}")
    public ResponseEntity<Map<String, Object>> deleteGeneralRule(@PathVariable String entryId) {
        boolean removed = knowledgeBaseService.deleteGeneralRule(entryId);
        return ResponseEntity.ok(Map.of("success", removed, "message", removed ? "已删除" : "未找到该规则"));
    }

    /**
     * 知识库统计信息。
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> stats() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("entryCount", knowledgeBaseService.getEntryCount());
        resp.put("stockCount", knowledgeBaseService.getStockCodes().size());
        return ResponseEntity.ok(resp);
    }

    /**
     * 清空所有知识条目和索引。
     */
    @DeleteMapping("/clear")
    public ResponseEntity<Map<String, Object>> clear() {
        knowledgeBaseService.clear();
        return ResponseEntity.ok(Map.of("success", true, "message", "知识库已清空"));
    }

    /**
     * 重建所有索引（向量 + BM25）。
     */
    @PostMapping("/rebuild")
    public ResponseEntity<Map<String, Object>> rebuild() {
        knowledgeBaseService.rebuildAll();
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "知识库索引重建完成",
                "entryCount", knowledgeBaseService.getEntryCount()));
    }
}