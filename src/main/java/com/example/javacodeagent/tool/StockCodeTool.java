package com.example.javacodeagent.tool;

import com.example.javacodeagent.model.WatchlistStock;
import com.example.javacodeagent.repository.WatchlistRepository;
import com.example.javacodeagent.service.AgentTracerService;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * 股票名称解析工具：将中文股票名称转为东方财富 secid 代码
 *
 * 技术亮点：
 * 1. LLM 不知道股票代码映射，需要这个工具作为"翻译层"
 * 2. 当 LLM 收到"分析贵州茅台"时，会先调用此工具获取代码
 * 3. 然后拿着代码去调用行情/财务工具
 * 4. 支持模糊匹配（如"茅台"匹配"贵州茅台"）
 */
@Component
public class StockCodeTool {

    private static final Logger log = LoggerFactory.getLogger(StockCodeTool.class);

    private final AgentTracerService tracer;
    private final WatchlistRepository watchlistRepository;
    private final ToolExecutorSupport support;

    private final Map<String, String> stockMap = new HashMap<>();
    private final Random random = new Random();

    public StockCodeTool(AgentTracerService tracer, WatchlistRepository watchlistRepository,
                         ToolExecutorSupport support) {
        this.tracer = tracer;
        this.watchlistRepository = watchlistRepository;
        this.support = support;
        initDefaultStocks();
    }

    private void initDefaultStocks() {
        stockMap.put("贵州茅台", "1.600519");
        stockMap.put("茅台", "1.600519");
        stockMap.put("宁德时代", "0.300750");
        stockMap.put("中国平安", "1.601318");
        stockMap.put("五粮液", "0.000858");
        stockMap.put("招商银行", "1.600036");
        stockMap.put("海康威视", "0.002415");
        stockMap.put("东方财富", "0.300059");
        stockMap.put("美的集团", "0.000333");
        stockMap.put("格力电器", "0.000651");
        stockMap.put("恒瑞医药", "1.600276");
        stockMap.put("伊利股份", "1.600887");
        stockMap.put("中信证券", "1.600030");
        stockMap.put("工商银行", "1.601398");
        stockMap.put("农业银行", "1.601288");
        stockMap.put("建设银行", "1.601939");
        stockMap.put("中国银行", "1.601988");
        stockMap.put("平安银行", "0.000001");
        stockMap.put("万科A", "0.000002");
        stockMap.put("中兴通讯", "0.000063");
        stockMap.put("比亚迪", "0.002594");
        stockMap.put("迈瑞医疗", "0.300760");
        stockMap.put("药明康德", "1.603259");
        stockMap.put("隆基绿能", "1.601012");
        stockMap.put("通威股份", "1.600438");
        stockMap.put("国泰集团", "1.603977");
    }

    @PostConstruct
    public void loadWatchlistStocks() {
        try {
            java.util.List<WatchlistStock> watchlist = watchlistRepository.findAll();
            for (WatchlistStock ws : watchlist) {
                String name = ws.getStockName();
                String code = ws.getStockCode();
                if (name != null && !name.isBlank() && code != null && !code.isBlank()) {
                    String normalizedCode = code;
                    if (code.matches("\\d{6}")) {
                        normalizedCode = code.startsWith("6") ? "1." + code : "0." + code;
                    }
                    stockMap.put(name, normalizedCode);
                    log.info("从自选股加载股票: {} -> {}", name, normalizedCode);
                }
            }
            log.info("StockCodeTool 初始化完成，共 {} 只股票", stockMap.size());
        } catch (Exception e) {
            log.warn("加载自选股股票失败（数据库可能未就绪）: {}", e.getMessage());
        }
    }

    @Tool("根据股票中文名称获取东方财富股票代码，例如：贵州茅台→1.600519")
    public String resolveCode(String stockName) {
        return tracer.traceToolCall("resolveCode", stockName, () -> {
            log.info("Tool 调用: resolveCode({})", stockName);
            return support.execute("resolveCode", "stockName", stockName, () -> {
                String code = stockMap.get(stockName);
                if (code != null) {
                    return String.format("股票 %s 的代码是 %s", stockName, code);
                }
                for (Map.Entry<String, String> entry : stockMap.entrySet()) {
                    if (entry.getKey().contains(stockName) || stockName.contains(entry.getKey())) {
                        return String.format("股票 %s 的代码是 %s（匹配到: %s）", stockName, entry.getValue(), entry.getKey());
                    }
                }
                code = lookupStockFromDb(stockName);
                if (code != null) {
                    stockMap.put(stockName, code);
                    return String.format("股票 %s 的代码是 %s", stockName, code);
                }
                return String.format("未找到股票 %s 的代码，请确认股票名称是否正确", stockName);
            }, e -> ToolErrors.nameError("resolveCode", stockName, e));
        });
    }

    /**
     * 给定一段文本，查找其中是否包含已知股票名称，返回代码或 null
     * （非 @Tool 方法，供内部缓存/路由使用）
     */
    public String findStockCode(String text) {
        if (text == null || text.isBlank()) return null;
        // 1. 扫描内存映射
        for (Map.Entry<String, String> entry : stockMap.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        // 2. 兜底：查自选股数据库（处理运行时新增的自选股）
        try {
            java.util.List<WatchlistStock> list = watchlistRepository.findAll();
            for (WatchlistStock ws : list) {
                if (ws.getStockName() != null && text.contains(ws.getStockName())) {
                    String normalizedCode = normalizeStockCode(ws.getStockCode());
                    stockMap.put(ws.getStockName(), normalizedCode);
                    return normalizedCode;
                }
            }
        } catch (Exception e) {
            log.warn("查询自选股数据库失败: {}", e.getMessage());
        }
        return null;
    }

    // ---- 通用方法 ----

    /** 将 6 位纯数字代码转为东方财富 secid 格式 */
    private String normalizeStockCode(String code) {
        if (code == null || code.isBlank()) return code;
        if (code.matches("\\d{6}")) {
            return code.startsWith("6") ? "1." + code : "0." + code;
        }
        return code;
    }

    /** 在自选股数据库中按股票名称查找代码（兜底，处理运行时新增的自选股） */
    private String lookupStockFromDb(String stockName) {
        try {
            java.util.List<WatchlistStock> list = watchlistRepository.findAll();
            for (WatchlistStock ws : list) {
                String name = ws.getStockName();
                if (name == null) continue;
                if (name.equals(stockName) || name.contains(stockName) || stockName.contains(name)) {
                    String code = normalizeStockCode(ws.getStockCode());
                    log.info("自选股数据库兜底命中: {} -> {}", stockName, code);
                    return code;
                }
            }
        } catch (Exception e) {
            log.warn("查询自选股数据库失败: {}", e.getMessage());
        }
        return null;
    }
}
