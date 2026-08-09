package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.service.StockNewsService;
import com.example.javacodeagent.vo.StockNewsVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StockNewsTool {

    private static final Logger log = LoggerFactory.getLogger(StockNewsTool.class);

    private final StockNewsService stockNewsService;
    private final AgentTracerService tracer;
    private final ToolExecutorSupport support;

    public StockNewsTool(StockNewsService stockNewsService, AgentTracerService tracer,
                         ToolExecutorSupport support) {
        this.stockNewsService = stockNewsService;
        this.tracer = tracer;
        this.support = support;
    }

    @Tool("获取股票相关新闻，通过新浪财经抓取返回标题和链接")
    public String getNews(String keyword) {
        return tracer.traceToolCall("getNews", keyword, () -> {
            log.info("Tool 调用: getNews({})", keyword);
            return support.execute("getNews", "keyword", keyword, () -> {
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
            }, e -> ToolErrors.keywordError("getNews", keyword, e, "获取新闻"));
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
