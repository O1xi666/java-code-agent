package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.service.StockNewsService;
import com.example.javacodeagent.vo.StockNewsVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
public class StockNewsTool {

    private static final Logger log = LoggerFactory.getLogger(StockNewsTool.class);
    private static final int TOOL_MAX_RETRIES = 3;
    private static final Random TOOL_RETRY_RANDOM = new Random();

    private final StockNewsService stockNewsService;
    private final AgentTracerService tracer;

    public StockNewsTool(StockNewsService stockNewsService, AgentTracerService tracer) {
        this.stockNewsService = stockNewsService;
        this.tracer = tracer;
    }

    @Tool("获取股票相关新闻，通过新浪财经抓取返回标题和链接")
    public String getNews(String keyword) {
        return tracer.traceToolCall("getNews", keyword, () -> {
            log.info("Tool 调用: getNews({})", keyword);
            Exception lastEx = null;
            for (int at = 1; at <= TOOL_MAX_RETRIES; at++) {
                try {
                    List<StockNewsVO> newsList = stockNewsService.getLatestNews(keyword);
                    if (newsList.isEmpty()) {
                        return "未获取到相关新闻";
                    }
                    StringBuilder sb = new StringBuilder("相关新闻：\n");
                    for (int i = 0; i < Math.min(newsList.size(), 5); i++) {
                        StockNewsVO news = newsList.get(i);
                        sb.append(String.format("%d. %s\n   来源：%s | 日期：%s\n   摘要：%s\n\n",
                                i + 1, news.getTitle(), news.getSource(), news.getDate(), news.getSummary()));
                    }
                    return sb.toString();
                } catch (Exception e) {
                    lastEx = e;
                    if (at < TOOL_MAX_RETRIES) {
                        int delayMs = 1000 + TOOL_RETRY_RANDOM.nextInt(2001);
                        try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
                    }
                }
            }
            log.error("获取新闻失败（已重试{}次）: {}", TOOL_MAX_RETRIES, lastEx.getMessage());
            return "获取新闻临时失败，已自动重试" + TOOL_MAX_RETRIES + "次，请稍后重新分析（" + lastEx.getMessage() + "）";
        });
    }
}

/*
旧 getNews 实现
            try {
                List<StockNewsVO> newsList = stockNewsService.getLatestNews(keyword);
                if (newsList.isEmpty()) {
                    return "未获取到相关新闻";
                }

                StringBuilder sb = new StringBuilder("相关新闻：\n");
                for (int i = 0; i < Math.min(newsList.size(), 5); i++) {
                    StockNewsVO news = newsList.get(i);
                    sb.append(String.format("%d. %s\n   来源：%s | 日期：%s\n   摘要：%s\n\n",
                            i + 1, news.getTitle(), news.getSource(), news.getDate(), news.getSummary()));
                }
                return sb.toString();
            } catch (Exception e) {
                log.error("获取新闻失败: {}", e.getMessage());
                return "获取新闻失败：" + e.getMessage();
            }
        });
    }
*/
