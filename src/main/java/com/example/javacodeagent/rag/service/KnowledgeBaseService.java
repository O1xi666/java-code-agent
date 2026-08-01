package com.example.javacodeagent.rag.service;

import com.example.javacodeagent.rag.model.KnowledgeEntry;
import com.example.javacodeagent.rag.util.BM25Searcher;
import com.example.javacodeagent.rag.util.SimHash;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import com.example.javacodeagent.rag.util.ChunkUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * 投研知识库 RAG 服务
 *
 * <p>独立管理自己的向量存储和BM25索引，支持：
 * <ul>
 *   <li>单条插入知识条目（绑定股票代码、分类、标签）</li>
 *   <li>CSV批量导入 → 自动分块 → 向量化 → 索引</li>
 *   <li>按股票代码+查询语义检索，返回带有引用来源的结果</li>
 *   <li>构建可直接喂入LLM的上下文（含引用标注）</li>
 *   <li>清空/重建索引</li>
 * </ul>
 *
 * <p>数据持久化：manifest.json（条目元数据）+ vector-store.json（向量）+ bm25-index/（BM25 Lucene）
 * 与DocumentService隔离，独立清除不影响文档索引。
 */
@Service
public class KnowledgeBaseService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseService.class);

    private static final String DATA_DIR = "rag-knowledge";
    private static final String MANIFEST_FILE = DATA_DIR + "/manifest.json";
    private static final String VECTOR_STORE_FILE = DATA_DIR + "/vector-store.json";
    private static final String BM25_INDEX_DIR = DATA_DIR + "/bm25-index";

    private static final int VECTOR_CANDIDATES = 30;
    private static final int DEFAULT_TOP_K = 5;
    /** 融合后进入精排的候选池大小 */
    private static final int RERANK_CANDIDATES = 30;
    private static final double VECTOR_WEIGHT = 0.70;
    private static final double BM25_WEIGHT = 0.30;

    private static final String META_ENTRY_ID = "entry_id";
    private static final String META_STOCK_CODE = "stock_code";
    private static final String META_STOCK_NAME = "stock_name";
    private static final String META_CATEGORY = "category";
    private static final String META_SOURCE = "source";
    private static final String META_TAGS = "tags";
    /** 通用规则的特殊股票代码——每次查询都会自动附带 */
    private static final String GENERAL_STOCK_CODE = "__GENERAL__";

    private final EmbeddingModel embeddingModel;
    private final BM25Searcher bm25Searcher;
    private final ObjectMapper objectMapper;
    private final RerankService rerankService;

    private InMemoryEmbeddingStore<TextSegment> vectorStore;
    private final List<KnowledgeEntry> entries = new CopyOnWriteArrayList<>();

    public KnowledgeBaseService(EmbeddingModel embeddingModel, RerankService rerankService) {
        this.embeddingModel = embeddingModel;
        this.bm25Searcher = new BM25Searcher(Path.of(BM25_INDEX_DIR));
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.rerankService = rerankService;
    }

    // ═══════════════════════════════════════════════════
    // 生命周期
    // ═══════════════════════════════════════════════════

    @PostConstruct
    public void init() {
        loadManifest();
        loadVectorStore();
        ensureBm25Index();
        log.info("知识库初始化完成: {} 条条目", entries.size());
    }

    private void loadManifest() {
        Path manifestPath = Path.of(MANIFEST_FILE);
        if (!Files.exists(manifestPath)) return;
        try {
            String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
            List<KnowledgeEntry> loaded = objectMapper.readValue(json,
                    new TypeReference<List<KnowledgeEntry>>() {});
            if (loaded != null) entries.addAll(loaded);
        } catch (Exception e) {
            log.warn("加载manifest失败: {}", e.getMessage());
        }
    }

    private void loadVectorStore() {
        Path storePath = Path.of(VECTOR_STORE_FILE);
        if (!Files.exists(storePath)) {
            vectorStore = new InMemoryEmbeddingStore<>();
            return;
        }
        try {
            String json = Files.readString(storePath, StandardCharsets.UTF_8);
            vectorStore = InMemoryEmbeddingStore.fromJson(json);
        } catch (Exception e) {
            log.warn("加载向量存储失败: {}", e.getMessage());
            vectorStore = new InMemoryEmbeddingStore<>();
        }
    }

    private void ensureBm25Index() {
        if (!Files.exists(Path.of(BM25_INDEX_DIR, "segments_1")) && !entries.isEmpty()) {
            rebuildBm25Index();
        }
    }

    // ═══════════════════════════════════════════════════
    // 写入
    // ═══════════════════════════════════════════════════

    public KnowledgeEntry insert(String stockCode, String stockName, String content,
                                 String category, String source, String tags) {
        KnowledgeEntry entry = KnowledgeEntry.fromRow(stockCode, stockName, content, category, source, tags);
        return insertInternal(entry);
    }

    private synchronized KnowledgeEntry insertInternal(KnowledgeEntry entry) {
        entries.add(entry);

        Embedding embedding = embedText(entry.getContent());
        TextSegment segment = metadataSegment(entry);
        vectorStore.add(embedding, segment);

        bm25Searcher.createOrReplaceIndex(buildBm25Input());

        persistAll();
        log.info("知识库已插入条目: {}", entry);
        return entry;
    }

    private synchronized List<KnowledgeEntry> insertBatch(List<KnowledgeEntry> newEntries) {
        if (newEntries == null || newEntries.isEmpty()) return List.of();

        entries.addAll(newEntries);

        List<Embedding> embeddings = new ArrayList<>(newEntries.size());
        List<TextSegment> segments = new ArrayList<>(newEntries.size());
        for (KnowledgeEntry entry : newEntries) {
            embeddings.add(embedText(entry.getContent()));
            segments.add(metadataSegment(entry));
        }
        vectorStore.addAll(embeddings, segments);
        bm25Searcher.createOrReplaceIndex(buildBm25Input());
        persistAll();
        log.info("知识库已批量插入 {} 条条目", newEntries.size());
        return newEntries;
    }

    // ═══════════════════════════════════════════════════
    // CSV导入
    // ═══════════════════════════════════════════════════

    /**
     * 预期CSV列：stock_code,stock_name,category,content,tags,source
     */
    public CsvImportResult importCsv(MultipartFile csvFile) {
        if (csvFile == null || csvFile.isEmpty()) {
            throw new IllegalArgumentException("CSV文件为空");
        }

        List<KnowledgeEntry> parsed = new ArrayList<>();
        int totalRows = 0;
        int skippedRows = 0;
        List<String> errors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvFile.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) throw new IllegalArgumentException("CSV文件为空");
            if (headerLine.length() > 0 && headerLine.charAt(0) == '\uFEFF') {
                headerLine = headerLine.substring(1);
            }

            int lineNum = 1;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                totalRows++;
                if (line.isBlank() || line.trim().startsWith("#")) {
                    skippedRows++;
                    continue;
                }
                try {
                    String[] fields = parseCsvLine(line);
                    if (fields.length < 4) {
                        skippedRows++;
                        errors.add("第" + lineNum + "行字段不足(需要至少4列)");
                        continue;
                    }
                    String stockCode = fields[0].trim();
                    String stockName = fields[1].trim();
                    String content = fields[3].trim();
                    String category = fields.length > 2 ? fields[2].trim() : "general";
                    String tags = fields.length > 4 ? fields[4].trim() : "";
                    String source = fields.length > 5 ? fields[5].trim() : "";

                    if (stockCode.isBlank() || content.isBlank()) {
                        skippedRows++;
                        errors.add("第" + lineNum + "行缺少股票代码或内容");
                        continue;
                    }
                    for (ChunkUtils.Chunk chunk : ChunkUtils.chunkByToken(content, "csv:" + stockCode + ":" + stockName, 300, 0.10)) {
                        parsed.add(KnowledgeEntry.fromRow(stockCode, stockName, chunk.content(), category, source, tags));
                    }
                } catch (Exception e) {
                    skippedRows++;
                    errors.add("第" + lineNum + "行解析失败: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("CSV读取失败: " + e.getMessage(), e);
        }

        if (parsed.isEmpty()) {
            return new CsvImportResult(0, totalRows, skippedRows, errors, "未能解析到有效数据");
        }
        insertBatch(parsed);
        String summary = String.format("成功导入 %d 条(含分块), 原行数 %d, 跳过 %d",
                parsed.size(), totalRows, skippedRows);
        return new CsvImportResult(parsed.size(), totalRows, skippedRows, errors, summary);
    }

    // ═══════════════════════════════════════════════════
    // 检索
    // ═══════════════════════════════════════════════════

    public List<RetrievedKnowledge> retrieve(String query, String stockCode, int topK) {
        if (query == null || query.isBlank()) return List.of();
        if (vectorStore == null || entries.isEmpty()) return List.of();

        int k = topK > 0 ? topK : DEFAULT_TOP_K;
        boolean filterByStock = stockCode != null && !stockCode.isBlank();
        int vectorK = filterByStock ? VECTOR_CANDIDATES * 2 : VECTOR_CANDIDATES;

        Embedding queryEmbedding = embedText(query);
        List<EmbeddingMatch<TextSegment>> vectorMatches = vectorStore.findRelevant(queryEmbedding, vectorK);
        List<BM25Searcher.Bm25Result> bm25Results = bm25Searcher.searchTopChunks(query);

        // 1. 融合召回生成候选池（供精排使用，不直接截断到 topK）
        List<RetrievedKnowledge> candidates = fuseAndFilter(
                vectorMatches, bm25Results,
                filterByStock ? stockCode.trim() : null,
                RERANK_CANDIDATES, query);

        // 2. 精排后取 TopK；精排不可用时回退到融合排序
        return rerankAndSelect(candidates, query, k);
    }

    /**
     * 对候选列表执行精排并截取 topK。
     * 精排失败或不可用时，直接按融合排序顺序取前 topK。
     */
    private List<RetrievedKnowledge> rerankAndSelect(
            List<RetrievedKnowledge> candidates, String query, int topK) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<String> docs = candidates.stream()
                .map(RetrievedKnowledge::getContent)
                .toList();
        List<RerankService.RerankResult> results = rerankService.rerank(query, docs, topK);

        if (results.isEmpty()) {
            return candidates.size() <= topK ? candidates : candidates.subList(0, topK);
        }

        List<RetrievedKnowledge> out = new ArrayList<>(results.size());
        for (RerankService.RerankResult rr : results) {
            int idx = rr.index();
            if (idx < 0 || idx >= candidates.size()) {
                continue;
            }
            RetrievedKnowledge c = candidates.get(idx);
            out.add(new RetrievedKnowledge(
                    c.getEntryId(), c.getStockCode(), c.getStockName(),
                    c.getContent(), c.getCategory(), c.getSource(), c.getTags(),
                    rr.score()));
        }
        return out;
    }


    /**
     * 插入通用规则（stockCode = __GENERAL__，每次查询自动附带）
     */
    public KnowledgeEntry insertGeneralRule(String content, String source, String tags) {
        return insert(GENERAL_STOCK_CODE, "通用规则", content, "general_rule", source, tags);
    }

    /**
     * 获取所有通用规则
     */
    public List<KnowledgeEntry> getGeneralRules() {
        return entries.stream()
                .filter(e -> GENERAL_STOCK_CODE.equals(e.getStockCode()))
                .toList();
    }

    /**
     * 删除指定通用规则
     */
    public boolean deleteGeneralRule(String entryId) {
        boolean removed = entries.removeIf(e -> e.getId().equals(entryId));
        if (removed) {
            rebuildAll();
        }
        return removed;
    }

    public List<RetrievedKnowledge> retrieve(String query) {
        return retrieve(query, null, DEFAULT_TOP_K);
    }

    /**
     * 构建LLM上下文文本，每行含引用编号、股票名称代码、分类、来源。
     */
    public String buildKnowledgeContext(String query, String stockCode) {
        StringBuilder sb = new StringBuilder();

        // 1. 通用规则（每次查询都自动附带）
        List<RetrievedKnowledge> generalRules = retrieve(query, GENERAL_STOCK_CODE, 20);
        if (!generalRules.isEmpty()) {
            sb.append("【通用规则】\n");
            for (int i = 0; i < generalRules.size(); i++) {
                RetrievedKnowledge rk = generalRules.get(i);
                sb.append(String.format("[通用%d] %s\n", i + 1, rk.getContent()));
            }
            sb.append("\n");
        }

        // 2. 股票专有知识
        if (stockCode != null && !stockCode.isBlank()) {
            List<RetrievedKnowledge> results = retrieve(query, stockCode, DEFAULT_TOP_K);
            if (!results.isEmpty()) {
                sb.append("【参考知识】\n");
                for (int i = 0; i < results.size(); i++) {
                    RetrievedKnowledge rk = results.get(i);
                    sb.append(String.format("[引用%d] %s(%s) | 类别:%s | 来源:%s | %s\n",
                            i + 1, rk.getStockName(), rk.getStockCode(),
                            rk.getCategory(), rk.getSource(), rk.getContent()));
                }
            }
        }

        return sb.toString();
    }

    // ═══════════════════════════════════════════════════
    // 管理
    // ═══════════════════════════════════════════════════

    public synchronized void clear() {
        entries.clear();
        vectorStore = new InMemoryEmbeddingStore<>();
        bm25Searcher.createOrReplaceIndex(List.of());
        try {
            Files.deleteIfExists(Path.of(MANIFEST_FILE));
            Files.deleteIfExists(Path.of(VECTOR_STORE_FILE));
        } catch (Exception e) {
            log.warn("删除知识库文件时出错: {}", e.getMessage());
        }
        log.info("知识库已清空");
    }

    public synchronized void rebuildAll() {
        log.info("开始重建知识库索引 ({} 条)...", entries.size());
        vectorStore = new InMemoryEmbeddingStore<>();
        List<Embedding> embeddings = new ArrayList<>(entries.size());
        List<TextSegment> segments = new ArrayList<>(entries.size());
        for (KnowledgeEntry entry : entries) {
            embeddings.add(embedText(entry.getContent()));
            segments.add(metadataSegment(entry));
        }
        if (!embeddings.isEmpty()) vectorStore.addAll(embeddings, segments);
        bm25Searcher.createOrReplaceIndex(buildBm25Input());
        persistAll();
        log.info("知识库索引重建完成");
    }

    public Set<String> getStockCodes() {
        return entries.stream()
                .map(KnowledgeEntry::getStockCode)
                .filter(c -> !c.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public int getEntryCount() { return entries.size(); }

    public List<KnowledgeEntry> getAllEntries() { return List.copyOf(entries); }

    // ═══════════════════════════════════════════════════
    // 内部方法
    // ═══════════════════════════════════════════════════

    private Embedding embedText(String text) {
        try {
            var response = embeddingModel.embed(text);
            if (response == null || response.content() == null) {
                throw new RuntimeException("Embedding生成失败");
            }
            return response.content();
        } catch (Exception e) {
            throw new RuntimeException("Embedding调用失败: " + e.getMessage(), e);
        }
    }

    private TextSegment metadataSegment(KnowledgeEntry entry) {
        TextSegment seg = TextSegment.from(entry.getContent());
        seg.metadata().put(META_ENTRY_ID, entry.getId());
        seg.metadata().put(META_STOCK_CODE, entry.getStockCode());
        seg.metadata().put(META_STOCK_NAME, entry.getStockName());
        seg.metadata().put(META_CATEGORY, entry.getCategory());
        seg.metadata().put(META_SOURCE, entry.getSource());
        seg.metadata().put(META_TAGS, entry.getTags());
        return seg;
    }

    private List<RetrievedKnowledge> fuseAndFilter(
            List<EmbeddingMatch<TextSegment>> vectorMatches,
            List<BM25Searcher.Bm25Result> bm25Results,
            String stockCodeFilter, int topK,
            String query) {

        Map<String, KnowledgeEntry> entryMap = entries.stream()
                .collect(Collectors.toMap(KnowledgeEntry::getId, e -> e, (a, b) -> a));
        Map<String, ScoreAcc> scoreMap = new java.util.HashMap<>();

        int totalV = vectorMatches.size();
        for (int i = 0; i < vectorMatches.size(); i++) {
            EmbeddingMatch<TextSegment> match = vectorMatches.get(i);
            TextSegment seg = match.embedded();
            if (seg == null) continue;
            String metaStock = seg.metadata().getString(META_STOCK_CODE);
            if (stockCodeFilter != null && !stockCodeFilter.equals(metaStock)) continue;

            ScoreAcc acc = scoreMap.computeIfAbsent(seg.metadata().getString(META_ENTRY_ID), k -> new ScoreAcc());
            acc.vectorScore = (double) (totalV - i) / totalV * VECTOR_WEIGHT;
            acc.totalScore += acc.vectorScore;
            acc.fill(seg);
        }

        int totalB = bm25Results.size();
        for (int i = 0; i < bm25Results.size(); i++) {
            BM25Searcher.Bm25Result bm25 = bm25Results.get(i);
            KnowledgeEntry entry = entryMap.get(bm25.chunkId());
            if (entry == null) continue;
            if (stockCodeFilter != null && !stockCodeFilter.equals(entry.getStockCode())) continue;

            ScoreAcc acc = scoreMap.computeIfAbsent(entry.getId(), k -> new ScoreAcc());
            acc.bm25Score = (double) (totalB - i) / totalB * BM25_WEIGHT;
            acc.totalScore += acc.bm25Score;
            if (acc.stockCode == null) acc.fill(entry);
        }

        // ── 细排：1. 股票名/代码匹配 ──
        if (query != null && !query.isBlank()) {
            for (ScoreAcc a : scoreMap.values()) {
                if (a.stockCode != null && !a.stockCode.isBlank()) {
                    String code = a.stockCode.contains(".") ? a.stockCode.substring(2) : a.stockCode;
                    if (query.contains(code)) { a.totalScore += 0.30; continue; }
                }
                if (a.stockName != null && !a.stockName.isBlank()) {
                    if (query.contains(a.stockName)) {
                        a.totalScore += 0.25;
                    } else {
                        for (int i = 0; i < a.stockName.length() - 1; i++) {
                            if (query.contains(a.stockName.substring(i, i + 2))) {
                                a.totalScore += 0.10; break;
                            }
                        }
                    }
                }
            }
        }

        // ── 细排：2. 时间权重 ──
        {
            Instant now = Instant.now();
            for (java.util.Map.Entry<String, ScoreAcc> e : scoreMap.entrySet()) {
                KnowledgeEntry ke = entryMap.get(e.getKey());
                if (ke != null && ke.getCreatedAt() != null) {
                    long days = ChronoUnit.DAYS.between(ke.getCreatedAt(), now);
                    double m;
                    if (days <= 7) m = 1.50;
                    else if (days <= 90) m = 1.20;
                    else if (days <= 365) m = 0.80;
                    else m = 0.30;
                    e.getValue().totalScore *= m;
                }
            }
        }

        // ── 细排：3. simHash 去重 ──
        {
            java.util.List<String> idList = new java.util.ArrayList<>(scoreMap.keySet());
            long[] fps = new long[idList.size()];
            for (int i = 0; i < idList.size(); i++) {
                ScoreAcc a = scoreMap.get(idList.get(i));
                fps[i] = (a != null && a.content != null) ? SimHash.compute(a.content) : 0L;
            }
            for (int i = 0; i < idList.size(); i++) {
                for (int j = i + 1; j < idList.size(); j++) {
                    if (fps[i] != 0L && fps[j] != 0L && SimHash.isDuplicate(fps[i], fps[j])) {
                        ScoreAcc a = scoreMap.get(idList.get(i));
                        ScoreAcc b = scoreMap.get(idList.get(j));
                        if (a != null && b != null) {
                            if (a.totalScore > b.totalScore) b.totalScore -= 0.50;
                            else a.totalScore -= 0.50;
                        }
                    }
                }
            }
        }

        return scoreMap.values().stream()
                .sorted(Comparator.comparingDouble((ScoreAcc a) -> a.totalScore).reversed())
                .limit(topK)
                .map(acc -> new RetrievedKnowledge(
                        acc.entryId, acc.stockCode, acc.stockName, acc.content,
                        acc.category, acc.source, acc.tags, acc.totalScore))
                .toList();
    }



    private String[] parseCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"'); i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                fields.add(cur.toString()); cur = new StringBuilder();
            } else {
                cur.append(c);
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }

    private List<BM25Searcher.Bm25Chunk> buildBm25Input() {
        return entries.stream()
                .map(e -> new BM25Searcher.Bm25Chunk(e.getId(), e.getContent()))
                .toList();
    }

    private void rebuildBm25Index() {
        bm25Searcher.createOrReplaceIndex(buildBm25Input());
    }

    private void persistAll() {
        try {
            Files.createDirectories(Path.of(DATA_DIR));
            String manifestJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries);
            Files.writeString(Path.of(MANIFEST_FILE), manifestJson, StandardCharsets.UTF_8);
            String vectorJson = vectorStore.serializeToJson();
            Files.writeString(Path.of(VECTOR_STORE_FILE), vectorJson, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("知识库持久化失败: {}", e.getMessage(), e);
        }
    }

    // ═══ 内部类 ═══

    private static final class ScoreAcc {
        String entryId, content, stockCode, stockName, category, source, tags;
        double totalScore, vectorScore, bm25Score;

        void fill(TextSegment seg) {
            this.entryId = seg.metadata().getString("entry_id");
            this.stockCode = seg.metadata().getString("stock_code");
            this.stockName = seg.metadata().getString("stock_name");
            this.category = seg.metadata().getString("category");
            this.source = seg.metadata().getString("source");
            this.tags = seg.metadata().getString("tags");
            this.content = seg.text();
        }
        void fill(KnowledgeEntry e) {
            this.entryId = e.getId();
            this.stockCode = e.getStockCode();
            this.stockName = e.getStockName();
            this.category = e.getCategory();
            this.source = e.getSource();
            this.tags = e.getTags();
            this.content = e.getContent();
        }
    }

    /**
     * 检索结果DTO。
     */
    public static final class RetrievedKnowledge {
        private final String entryId;
        private final String stockCode;
        private final String stockName;
        private final String content;
        private final String category;
        private final String source;
        private final String tags;
        private final double score;

        public RetrievedKnowledge(String entryId, String stockCode, String stockName,
                                  String content, String category, String source,
                                  String tags, double score) {
            this.entryId = entryId;
            this.stockCode = stockCode;
            this.stockName = stockName;
            this.content = content;
            this.category = category;
            this.source = source;
            this.tags = tags;
            this.score = score;
        }

        public String getEntryId() { return entryId; }
        public String getStockCode() { return stockCode; }
        public String getStockName() { return stockName; }
        public String getContent() { return content; }
        public String getCategory() { return category; }
        public String getSource() { return source; }
        public String getTags() { return tags; }
        public double getScore() { return score; }
    }

    /**
     * CSV导入结果。
     */
    public record CsvImportResult(int importedCount, int totalRows, int skippedRows,
                                   List<String> errors, String summary) {}
}
