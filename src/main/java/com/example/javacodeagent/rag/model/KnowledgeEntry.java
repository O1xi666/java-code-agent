package com.example.javacodeagent.rag.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 投研知识条目模型
 *
 * 每个条目标记到具体的股票代码，支持多维分类和来源溯源。
 * CSV导入时，一行数据对应一个KnowledgeEntry。
 */
public class KnowledgeEntry {

    private String id;
    private String stockCode;
    private String stockName;
    private String content;
    private String category;
    private String source;
    private String tags;
    private Instant createdAt;

    public KnowledgeEntry() {}

    public KnowledgeEntry(String stockCode, String stockName, String content,
                          String category, String source, String tags) {
        this.id = UUID.randomUUID().toString().substring(0, 12);
        this.stockCode = Objects.toString(stockCode, "").trim();
        this.stockName = Objects.toString(stockName, "").trim();
        this.content = Objects.toString(content, "").trim();
        this.category = Objects.toString(category, "general").trim();
        this.source = Objects.toString(source, "").trim();
        this.tags = Objects.toString(tags, "").trim();
        this.createdAt = Instant.now();
    }

    public static KnowledgeEntry fromRow(String stockCode, String stockName, String content,
                                          String category, String source, String tags) {
        return new KnowledgeEntry(stockCode, stockName, content, category, source, tags);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }
    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return String.format("KnowledgeEntry{id='%s', stock='%s', name='%s', category='%s', len=%d}",
                id, stockCode, stockName, category, content.length());
    }
}
