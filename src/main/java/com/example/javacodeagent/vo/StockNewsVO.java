package com.example.javacodeagent.vo;

import java.io.Serializable;

/**
 * 股票新闻数据 VO
 */
public class StockNewsVO implements Serializable {

    private String title;       // 新闻标题
    private String summary;     // 新闻摘要
    private String source;      // 来源
    private String date;        // 发布日期
    private String url;         // 原文链接
    private String sentiment;   // 情感倾向（positive/negative/neutral）

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSentiment() {
        return sentiment;
    }

    public void setSentiment(String sentiment) {
        this.sentiment = sentiment;
    }

    @Override
    public String toString() {
        return "StockNewsVO{" +
                "title='" + title + '\'' +
                ", sentiment='" + sentiment + '\'' +
                '}';
    }
}
