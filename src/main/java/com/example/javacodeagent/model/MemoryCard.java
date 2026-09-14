package com.example.javacodeagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 历史结论记忆卡片：长期记忆「历史结论层」的持久化形态。
 *
 * <p>每条结论是表里的一行,替代原先"整个结论列表序列化成一个 JSON 存进单个 Redis key"的做法。
 * 好处是可以按用户、状态、标的做 SQL 过滤,不必把整份列表取回应用层再遍历。
 *
 * <p>status 取值:ACTIVE(可注入上下文)、STALE(已失效)、ARCHIVED(仅追溯)。
 */
@Entity
@Table(name = "memory_card", indexes = {
        @Index(name = "idx_memory_card_user_status", columnList = "user_id,status"),
        @Index(name = "idx_memory_card_user_stock", columnList = "user_id,stock_code")
})
public class MemoryCard {

    /** 原 Conclusion#id,8 位随机串,作为主键天然幂等 */
    @Id
    @Column(name = "id", length = 32, nullable = false, updatable = false)
    private String id;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "stock_code", length = 20)
    private String stockCode;

    @Column(name = "stock_name", length = 100)
    private String stockName;

    @Column(name = "question", length = 255)
    private String question;

    @Column(name = "summary", length = 512)
    private String summary;

    @Column(name = "score", length = 20)
    private String score;

    @Column(name = "direction", length = 10)
    private String direction;

    @Column(name = "confidence", length = 10)
    private String confidence;

    @Column(name = "fact_checked", nullable = false)
    private boolean factChecked;

    /** ACTIVE / STALE / ARCHIVED */
    @Column(name = "status", length = 16, nullable = false)
    private String status;

    @Column(name = "invalid_reason", length = 255)
    private String invalidReason;

    /** epoch millis,与业务层的有效期判断保持一致 */
    @Column(name = "created_at", nullable = false)
    private long createdAt;

    @Column(name = "expires_at", nullable = false)
    private long expiresAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getStockCode() { return stockCode; }
    public void setStockCode(String stockCode) { this.stockCode = stockCode; }

    public String getStockName() { return stockName; }
    public void setStockName(String stockName) { this.stockName = stockName; }

    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getScore() { return score; }
    public void setScore(String score) { this.score = score; }

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public boolean isFactChecked() { return factChecked; }
    public void setFactChecked(boolean factChecked) { this.factChecked = factChecked; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getInvalidReason() { return invalidReason; }
    public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
