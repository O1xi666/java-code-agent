package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.StockMarketService;
import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.tool.StockCodeTool;
import com.example.javacodeagent.vo.StockQuoteVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 股票行情工具：LLM 可调用的实时行情 / K 线 / 均线查询
 *
 * 技术亮点（面试关注点）：
 * 1. 使用 @Tool 注解声明为 LLM 可调用的工具函数
 * 2. 遵循 LangChain4j 的工具规范，Agent 自动根据问题决定是否调用
 * 3. 返回格式化字符串，LLM 可直接阅读
 * 4. 数据通过 StockMarketService 获取，而非直接在工具内实现
 *
 * Q: LLM 怎么知道什么时候调用这个工具？
 * A: LangChain4j 的 AiServices 会自动解析 @Tool 注解和描述，
 *    当 LLM 判断需要行情数据时，会自动生成函数调用参数
 */
@Component
public class StockMarketTool {

    private static final Logger log = LoggerFactory.getLogger(StockMarketTool.class);

    /** 将股票名称或代码统一为标准 secid 格式 */
    private String resolveSecid(String input) {
        if (input == null || input.isBlank()) return input;
        // 如果包含中文，可能是股票名称 → 用 StockCodeTool 解析
        if (input.matches(".*[\\u4e00-\\u9fa5].*")) {
            String resolved = stockCodeTool.resolveCode(input);
            // 解析结果如 "股票 贵州茅台 的代码是 1.600519"
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+)").matcher(resolved);
            if (m.find()) return m.group(1);
            return input;
        }
        // 如果是纯数字代码（如 600519），补上市场前缀
        if (input.matches("\\d{6}")) {
            String prefix = input.startsWith("6") ? "1." : "0.";
            return prefix + input;
        }
        return input;
    }

    private final StockMarketService stockMarketService;
    private final StockCodeTool stockCodeTool;
    private final AgentTracerService tracer;
    private final ToolExecutorSupport support;

    public StockMarketTool(StockMarketService stockMarketService, StockCodeTool stockCodeTool,
                           AgentTracerService tracer, ToolExecutorSupport support) {
        this.stockMarketService = stockMarketService;
        this.stockCodeTool = stockCodeTool;
        this.tracer = tracer;
        this.support = support;
    }

    /**
     * 获取股票实时行情
     *
     * @param secid 股票代码，格式：1.600519（上海）或 0.000001（深圳）
     * @return 格式化行情字符串
     */
    @Tool("获取股票当前实时行情（最新价、涨跌幅、开盘价、最高最低价）。要获取当前实时股价必须使用此工具，参数secid为股票代码")
    public String getQuote(String secid) {
        return tracer.traceToolCall("getQuote", secid, () -> {
            String resolved = resolveSecid(secid);
            log.info("Tool 调用: getQuote({})", resolved);
            return support.execute("getQuote", "secid", secid, () -> {
                StockQuoteVO quote = stockMarketService.getQuote(resolved);
                return String.format("""
                        股票：%s (%s)
                        最新价：%.2f
                        开盘价：%.2f
                            最高价：%.2f | 最低价：%.2f
                            涨跌幅：%.2f%%
                            成交量：%d 手
                            """,
                            quote.getName(), quote.getCode(),
                            quote.getPrice(), quote.getOpen(),
                        quote.getHigh(), quote.getLow(),
                        quote.getChange(),
                        quote.getVolume()
                );
            }, e -> ToolErrors.secidError("getQuote", secid, e, "获取实时行情"));
        });
    }

    /**
     * 获取股票 K 线数据（日线）
     *
     * @param secid 股票代码
     * @param days  天数（最多 120）
     * @return 格式化 K 线字符串
     */
    // getDailyKLine 不暴露为独立工具（通过 getIndicators 内部使用）
    public String getDailyKLine(String secid, int days) {
        secid = resolveSecid(secid);
        log.info("Tool 调用: getDailyKLine({}, {})", secid, days);
        try {
            List<StockKLineVO> klineList = stockMarketService.getDailyKLine(secid, Math.min(days, 120));
            if (klineList.isEmpty()) {
                return "未获取到 K 线数据";
            }

            // 返回最后 10 条（避免 token 过长）
            StringBuilder sb = new StringBuilder("最近 K 线数据：\n");
            int start = Math.max(0, klineList.size() - 10);
            for (int i = start; i < klineList.size(); i++) {
                StockKLineVO k = klineList.get(i);
                sb.append(String.format("%s 开盘:%.2f 收盘:%.2f 最高:%.2f 最低:%.2f 成交量:%d\n",
                        k.getDate(), k.getOpen(), k.getClose(), k.getHigh(), k.getLow(), k.getVolume()));
            }

            // 计算均线
            List<Double> maList = stockMarketService.calculateMA(klineList);
            if (!maList.isEmpty()) {
                sb.append("\n均线：\n");
                String[] labels = {"MA5", "MA10", "MA20", "MA60"};
                for (int i = 0; i < maList.size() && i < labels.length; i++) {
                    sb.append(String.format("%s: %.2f  ", labels[i], maList.get(i)));
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.error("获取 K 线数据失败: {}", e.getMessage());
            return "获取 K 线数据失败：" + e.getMessage();
        }
    }
}
