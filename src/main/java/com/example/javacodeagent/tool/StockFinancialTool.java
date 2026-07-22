package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.service.StockFinancialService;
import com.example.javacodeagent.vo.StockFinancialVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

@Component
public class StockFinancialTool {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialTool.class);
    private static final int TOOL_MAX_RETRIES = 3;
    private static final Random TOOL_RETRY_RANDOM = new Random();

    private final StockFinancialService stockFinancialService;
    private final AgentTracerService tracer;

    public StockFinancialTool(StockFinancialService stockFinancialService, AgentTracerService tracer) {
        this.stockFinancialService = stockFinancialService;
        this.tracer = tracer;
    }

    @Tool("获取股票财务数据，包括营收、利润、ROE、毛利率、净利率、市盈率、市净率、资产负债率等")
    public String getFinancials(String secid) {
        return tracer.traceToolCall("getFinancials", secid, () -> {
            log.info("Tool 调用: getFinancials({})", secid);
            Exception lastEx = null;
            for (int at = 1; at <= TOOL_MAX_RETRIES; at++) {
                try {
                    List<StockFinancialVO> financials = stockFinancialService.getRecentFinancials(secid);
                    if (financials.isEmpty()) {
                        return "未获取到财务数据";
                    }
                    StringBuilder sb = new StringBuilder("财务数据：\n");
                    for (StockFinancialVO fin : financials) {
                        sb.append(String.format("""
                                年份：%s
                                营业收入：%.2f
                                净利润：%.2f
                                ROE：%.2f%%
                                毛利率：%.2f%%
                                净利率：%.2f%%
                                市盈率：%.2f
                                市净率：%.2f
                                资产负债率：%.2f%%
                                资产周转率：%.2f
                                """,
                                fin.getYear(), fin.getRevenue(), fin.getProfit(),
                                fin.getRoE(), fin.getGrossMargin(), fin.getNetMargin(),
                                fin.getPe(), fin.getPb(), fin.getDebtRatio(), fin.getAssetTurnover()
                        ));
                        sb.append("---\n");
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
            log.error("获取财务数据失败（已重试{}次）: {}", TOOL_MAX_RETRIES, lastEx.getMessage());
            return "获取财务数据临时失败，已自动重试" + TOOL_MAX_RETRIES + "次，请稍后重新分析（" + lastEx.getMessage() + "）";
        });
    }
}

/*
旧 getFinancials 实现
            try {
                List<StockFinancialVO> financials = stockFinancialService.getRecentFinancials(secid);
                if (financials.isEmpty()) {
                    return "未获取到财务数据";
                }

                StringBuilder sb = new StringBuilder("财务数据：\n");
                for (StockFinancialVO fin : financials) {
                    sb.append(String.format("""
                            年份：%s
                            营业收入：%.2f
                            净利润：%.2f
                            ROE：%.2f%%
                            毛利率：%.2f%%
                            净利率：%.2f%%
                            市盈率：%.2f
                            市净率：%.2f
                            资产负债率：%.2f%%
                            资产周转率：%.2f
                            """,
                            fin.getYear(), fin.getRevenue(), fin.getProfit(),
                            fin.getRoE(), fin.getGrossMargin(), fin.getNetMargin(),
                            fin.getPe(), fin.getPb(), fin.getDebtRatio(), fin.getAssetTurnover()
                    ));
                    sb.append("---\n");
                }
                return sb.toString();
            } catch (Exception e) {
                log.error("获取财务数据失败: {}", e.getMessage());
                return "获取财务数据失败：" + e.getMessage();
            }
        });
    }
*/
