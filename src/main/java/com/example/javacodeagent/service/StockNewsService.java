package com.example.javacodeagent.service;

import com.example.javacodeagent.vo.StockNewsVO;
import com.example.javacodeagent.config.CachePolicy;
import com.example.javacodeagent.util.DataCacheManager;
import com.example.javacodeagent.util.SeleniumDriver;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class StockNewsService {

    private static final Logger log = LoggerFactory.getLogger(StockNewsService.class);

    private final SeleniumDriver seleniumDriver;
    private final DataCacheManager cacheManager;

    public StockNewsService(SeleniumDriver seleniumDriver, DataCacheManager cacheManager) {
        this.seleniumDriver = seleniumDriver;
        this.cacheManager = cacheManager;
    }

    public List<StockNewsVO> searchNews(String keyword, int pageIndex, int pageSize) {
        return getStockNews(keyword, Math.max(pageSize, 10));
    }

    public List<StockNewsVO> getLatestNews(String keyword) {
        return getStockNews(keyword, 5);
    }

    private List<StockNewsVO> getStockNews(String keyword, int maxCount) {
        String cacheKey = "stock:news:" + keyword;
        return cacheManager.getOrFetch(cacheKey, () -> fetchStockNews(keyword, maxCount), CachePolicy.STOCK_NEWS);
    }

    private List<StockNewsVO> fetchStockNews(String keyword, int maxCount) {
        try {
            String url = buildSinaUrl(keyword);
            if (url == null) return List.of();

            // 使用 Selenium 渲染 JS 内容，获取完整的页面 HTML
            String html = seleniumDriver.getContentAfterWaiting(url, "body", 5);
            if (html == null || html.isBlank()) return List.of();
            Document doc = Jsoup.parse(html);

            // 提取所有带链接的新闻标题
            List<StockNewsVO> newsList = new ArrayList<>();
            Elements links = doc.select("a[href]");
            for (Element link : links) {
                String text = link.text().trim();
                String href = link.attr("abs:href");
                if (text.length() < 6 || href.isBlank()) continue;
                if (href.contains("sina.com") || href.contains("finance")) {
                    StockNewsVO news = new StockNewsVO();
                    news.setTitle(text);
                    news.setUrl(href);
                    news.setSource("新浪财经");
                    news.setDate("");
                    news.setSentiment("neutral");
                    newsList.add(news);
                    if (newsList.size() >= maxCount) break;
                }
            }
            return newsList;

        } catch (Exception e) {
            log.warn("Selenium 抓取新浪新闻失败，尝试东方财富: {}", e.getMessage());
            // 降级：尝试从东方财富获取新闻
            try {
                return getNewsFromEastMoney(keyword, maxCount);
            } catch (Exception e2) {
                log.error("东方财富新闻也获取失败: {}", e2.getMessage());
                return List.of();
            }
        }
    }

    /**
     * 从东方财富搜索新闻（Selenium 渲染）
     */
    private List<StockNewsVO> getNewsFromEastMoney(String keyword, int maxCount) {
        String encoded = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String url = "https://so.eastmoney.com/news/s?keyword=" + encoded;
        String html = seleniumDriver.getContent(url);
        if (html == null || html.isBlank()) return List.of();

        Document doc = Jsoup.parse(html);
        List<StockNewsVO> newsList = new ArrayList<>();

        // 东方财富搜索结果：链接在 .news-item 或 .search-item 中
        Elements items = doc.select("a[href*=\"eastmoney.com\"]");
        for (Element link : items) {
            String text = link.text().trim();
            String href = link.attr("abs:href");
            if (text.length() < 6 || href.isBlank()) continue;
            StockNewsVO news = new StockNewsVO();
            news.setTitle(text);
            news.setUrl(href);
            news.setSource("东方财富");
            news.setDate("");
            news.setSentiment("neutral");
            newsList.add(news);
            if (newsList.size() >= maxCount) break;
        }
        return newsList;
    }

    private String buildSinaUrl(String keyword) {
        if (keyword == null) return null;
        String secid = resolveSecid(keyword);
        if (secid == null) return null;
        String sinaCode = secid.startsWith("1.") ? "sh" + secid.substring(2) : "sz" + secid.substring(2);
        log.info("构建新浪财经新闻 URL: code={}, sinaCode={}", secid, sinaCode);
        return "https://finance.sina.com.cn/realstock/company/" + sinaCode + "/nc.shtml";
    }

    private String resolveSecid(String keyword) {
        if (keyword.matches("\\d+\\.\\d+")) return keyword;
        if (keyword.matches("\\d{6}")) return keyword.startsWith("6") ? "1." + keyword : "0." + keyword;
        if (keyword.contains("茅台")) return "1.600519";
        if (keyword.contains("宁德")) return "0.300750";
        if (keyword.contains("平安")) return "1.601318";
        if (keyword.contains("比亚迪")) return "0.002594";
        if (keyword.contains("五粮液")) return "0.000858";
        if (keyword.contains("招商银行")) return "1.600036";
        return null;
    }
}
