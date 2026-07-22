package com.example.javacodeagent.config;

import java.util.concurrent.TimeUnit;

/**
 * 缓存策略：按数据类型定义差异化 TTL
 *
 * TTL 的设定依据：
 * - 行情：30s，股价每秒都在变，但 30s 内的旧数据对概览场景足够
 * - K 线：5min，日 K 一天才一根新柱子
 * - 财务：1h，财报按季度出
 * - 新闻：10min，非实时
 * - LLM 分析：20min，减少重复调用
 */
public enum CachePolicy {

    STOCK_QUOTE(30, TimeUnit.SECONDS),
    STOCK_KLINE(5, TimeUnit.MINUTES),
    STOCK_FINANCIAL(1, TimeUnit.HOURS),
    STOCK_NEWS(10, TimeUnit.MINUTES),
    LLM_ANALYSIS(20, TimeUnit.MINUTES);

    private final long ttl;
    private final TimeUnit unit;

    CachePolicy(long ttl, TimeUnit unit) {
        this.ttl = ttl;
        this.unit = unit;
    }

    public long ttl() { return ttl; }
    public TimeUnit unit() { return unit; }
}
