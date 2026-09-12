package com.example.javacodeagent.controller;

import com.example.javacodeagent.model.WatchlistStock;
import com.example.javacodeagent.service.StockAgent;
import com.example.javacodeagent.service.StockMarketService;
import com.example.javacodeagent.service.StockNewsService;
import com.example.javacodeagent.service.WatchlistService;
import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.vo.StockNewsVO;
import com.example.javacodeagent.vo.StockQuoteVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 股票分析 REST API
 *
 * 技术亮点（面试关注点）：
 * 1. 统一的请求/响应格式，便于前端对接
 * 2. analyze 接口使用 LangChain4j AiServices 解析问题并调用工具
 * 3. dailyReport 接口直接调用 Service 层，不经过 LLM，适合快速查询
 *
 * 接口设计：
 * POST /api/stock/analyze - 分析单只股票（LLM 驱动）
 * POST /api/stock/daily-report - 生成每日早报（本地模式，按需调用）
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private static final Logger log = LoggerFactory.getLogger(StockController.class);

    private final StockAgent stockAgent;
    private final StockMarketService stockMarketService;
    private final StockNewsService stockNewsService;
    private final WatchlistService watchlistService;

    // 默认自选股列表（用户可以自行修改）
    private static final List<String> DEFAULT_WATCHLIST = List.of(
            "1.600519",  // 贵州茅台 - 上海
            "0.300750",  // 宁德时代 - 深圳
            "1.601318",  // 中国平安 - 上海
            "0.000858",  // 五粮液 - 深圳
            "1.600036",  // 招商银行 - 上海
            "0.002415",  // 海康威视 - 深圳
            "0.300059",  // 东方财富 - 深圳
        "0.000333"   // 美的集团 - 深圳
    );

    public StockController(StockAgent stockAgent, StockMarketService stockMarketService,
                           StockNewsService stockNewsService, WatchlistService watchlistService) {
        this.stockAgent = stockAgent;
        this.stockMarketService = stockMarketService;
        this.stockNewsService = stockNewsService;
        this.watchlistService = watchlistService;
    }

    /**
     * 分析股票（LLM 驱动）
     *
     * @param body     用户输入，如 "分析一下贵州茅台，值得买吗？"
     * @param sessionId 会话 ID，用于记忆上下文
     * @return LLM 生成的结构化分析报告
     */
    @PostMapping("/analyze")
    public String analyze(
            @RequestBody String body,
            @RequestHeader(value = "X-Session-Id", defaultValue = "default-session") String sessionId
    ) {
        return stockAgent.analyze(body, sessionId);
    }

    /**
     * 流式分析股票（Server-Sent Events）
     * 返回 text/event-stream，逐字输出 LLM 的分析结果
     */
    @PostMapping(value = "/analyze/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> analyzeStream(
            @RequestBody String body,
            @RequestHeader(value = "X-Session-Id", defaultValue = "default-session") String sessionId
    ) {
        return stockAgent.analyzeStream(body, sessionId);
    }

    /**
     * 生成每日早报（本地模式，按需调用）
     *
     * 不经过 LLM，直接调用 Service 层获取实时数据并生成结构化报告。
     * 这样生成速度快，且不受 LLM 可用性影响。
     *
     * @param stockCodes 可选，股票代码列表，用逗号分隔。为空时优先使用用户在自选股表中维护的标的，
     *                   自选股为空或读取异常时回退到内置默认列表
     * @return 每日早报，包含每只股票的行情、涨跌幅和新闻摘要
     */
    @PostMapping("/daily-report")
    public Map<String, Object> dailyReport(
            @RequestParam(value = "codes", required = false, defaultValue = "") String stockCodes
    ) {
        List<String> codes = stockCodes.isBlank() ? resolveWatchlistCodes() : List.of(stockCodes.split(","));
        LocalDate today = LocalDate.now();

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("date", today.toString());
        report.put("totalStocks", codes.size());

        List<Map<String, Object>> stockList = new ArrayList<>();
        int alertCount = 0;

        for (String code : codes) {
                            Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", code);
                try {
                    StockQuoteVO quote = stockMarketService.getQuote(code);
                    item.put("name", quote.getName());
                    item.put("price", quote.getPrice());
                    item.put("change", quote.getChange());
                    item.put("changePercent", String.format("%.2f%%", quote.getChange()));
                    item.put("volume", quote.getVolume());
                                        if (quote.getMarketCap() != null) {
                        item.put("marketCap", String.format("%.2f亿", quote.getMarketCap() / 1e8));
                    } else {
                        item.put("marketCap", "N/A");
                    }
                    item.put("pe", quote.getPe() != null ? quote.getPe() : "N/A");

                    // 预警
                    if (Math.abs(quote.getChange()) > 3.0) {
                        item.put("alert", "⚠️ 异动: " + String.format("%.2f%%", quote.getChange()));
                        alertCount++;
                    } else {
                        item.put("alert", "正常");
                    }

                    // 新闻单独 try，失败不影响行情显示
                    try {
                        List<StockNewsVO> newsList = stockNewsService.getLatestNews(quote.getName());
                        List<String> headlines = new ArrayList<>();
                        for (int i = 0; i < Math.min(3, newsList.size()); i++) {
                            headlines.add(newsList.get(i).getTitle());
                        }
                        item.put("topNews", headlines);
                    } catch (Exception ignored) {
                        item.put("topNews", List.of("新闻获取暂不可用"));
                    }

                } catch (Exception e) {
                    item.put("error", "获取行情数据失败: " + e.getMessage());
                }
                stockList.add(item);
        }

        report.put("alerts", alertCount);
        report.put("stocks", stockList);

        return report;
    }

    /**
     * 解析默认股票代码列表：优先使用用户自选股，自选股为空或读取异常时回退默认列表
     */
    private List<String> resolveWatchlistCodes() {
        try {
            List<WatchlistStock> watchlist = watchlistService.listAll();
            if (watchlist != null && !watchlist.isEmpty()) {
                List<String> codes = new ArrayList<>();
                for (WatchlistStock stock : watchlist) {
                    String secid = toSecid(stock.getStockCode());
                    if (secid != null) {
                        codes.add(secid);
                    }
                }
                if (!codes.isEmpty()) {
                    log.info("日报使用用户自选股，共 {} 只", codes.size());
                    return codes;
                }
            }
            log.info("用户自选股为空，日报回退默认列表");
        } catch (Exception e) {
            log.warn("读取用户自选股失败，日报回退默认列表: {}", e.getMessage());
        }
        return DEFAULT_WATCHLIST;
    }

    /**
     * 将 6 位股票代码标准化为 secid（6 开头为上海 1.，其余为深圳 0.）
     */
    private String toSecid(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;
        String code = stockCode.trim();
        if (code.contains(".")) return code;
        if (code.matches("\\d{6}")) {
            return code.startsWith("6") ? "1." + code : "0." + code;
        }
        return code;
    }
}
