package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.service.StockIndicatorService;
import com.example.javacodeagent.service.StockMarketService;
import com.example.javacodeagent.vo.StockIndicatorVO;
import com.example.javacodeagent.vo.StockKLineVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
public class StockIndicatorTool {

    private static final Logger log = LoggerFactory.getLogger(StockIndicatorTool.class);
    private static final int TOOL_MAX_RETRIES = 3;
    private static final Random TOOL_RETRY_RANDOM = new Random();

    private final StockIndicatorService stockIndicatorService;
    private final StockMarketService stockMarketService;
    private final AgentTracerService tracer;

    public StockIndicatorTool(StockIndicatorService stockIndicatorService,
                              StockMarketService stockMarketService,
                              AgentTracerService tracer) {
        this.stockIndicatorService = stockIndicatorService;
        this.stockMarketService = stockMarketService;
        this.tracer = tracer;
    }

    @Tool("获取股票技术指标，包括MACD、RSI、KDJ、布林带，以及趋势判断和信号")
    public String getIndicators(String secid) {
        return tracer.traceToolCall("getIndicators", secid, () -> {
            log.info("Tool 调用: getIndicators({})", secid);
            try {
                List<StockKLineVO> klineList = stockMarketService.getDailyKLine(secid, 60);
                if (klineList.size() < 60) {
                    return "K 线数据不足（需要 60 天，当前 " + klineList.size() + " 天）";
                }

                StockIndicatorVO indicator = stockIndicatorService.calculateAll(klineList);

                StringBuilder sb = new StringBuilder("技术指标分析：\n");
                sb.append("⚠️ 指标基于不复权K线计算，若近期有除权除息，数值可能与主流软件存在偏差\n");
        sb.append("--- MACD ---\n");
                if (indicator.getMacdLine() != null && !indicator.getMacdLine().isEmpty()) {
                    sb.append(String.format("最新 DIF：%.2f  DEA：%.2f  BAR：%.2f\n",
                            indicator.getMacdLine().get(indicator.getMacdLine().size() - 1),
                            indicator.getSignalLine().get(indicator.getSignalLine().size() - 1),
                            indicator.getHistogram().get(indicator.getHistogram().size() - 1)));
                    sb.append("MACD 信号：").append(translateMacd(indicator.getMacdSignal())).append("\n");
                }

                sb.append("--- RSI ---\n");
                sb.append(String.format("RSI(6)：%.2f  RSI(14)：%.2f  RSI(24)：%.2f  %s\n", indicator.getRsi6(), indicator.getRsi14(), indicator.getRsi24(), translateRsi(indicator.getRsi6())));

                sb.append("--- KDJ ---\n");
                sb.append(String.format("K：%.2f  D：%.2f  J：%.2f\n",
                        indicator.getkValue(), indicator.getdValue(), indicator.getjValue()));

                sb.append("--- 布林带 ---\n");
                sb.append(String.format("上轨：%.2f  中轨：%.2f  下轨：%.2f\n",
                        indicator.getBollingerUpper(), indicator.getBollingerMiddle(), indicator.getBollingerLower()));

                sb.append("\n--- 综合判断 ---\n");
                sb.append("趋势方向：").append(translateTrend(indicator.getTrend())).append("\n");
                sb.append("动量状态：").append(translateMomentum(indicator.getMomentum())).append("\n");

                return sb.toString();
            } catch (Exception e) {
                log.error("获取技术指标失败: {}", e.getMessage());
                return "获取技术指标失败：" + e.getMessage();
            }
        });
    }

    private String translateMacd(String signal) {
        if ("golden_cross".equals(signal)) return "金叉（买入信号）";
        if ("death_cross".equals(signal)) return "死叉（卖出信号）";
        return "无明确信号";
    }

    private String translateRsi(Double rsi) {
        if (rsi == null) return "无数据";
        if (rsi > 70) return "超买区";
        if (rsi < 30) return "超卖区";
        return "正常区间";
    }

    private String translateTrend(String trend) {
        if ("bullish".equals(trend)) return "多头排列（看涨）";
        if ("bearish".equals(trend)) return "空头排列（看跌）";
        return "震荡（中性）";
    }

    private String translateMomentum(String momentum) {
        if ("overbought".equals(momentum)) return "超买（震荡/弱趋势，回调风险较高）";
        if ("overbought_trend_up".equals(momentum)) return "超买（多头趋势延续中，KDJ可能高位钝化，仅为预警）";
        if ("oversold".equals(momentum)) return "超卖（可能反弹）";
        if ("oversold_trend_down".equals(momentum)) return "超卖（空头延续中，可能继续下探，需等明确止跌信号）";
        return "中性";
    }
}
