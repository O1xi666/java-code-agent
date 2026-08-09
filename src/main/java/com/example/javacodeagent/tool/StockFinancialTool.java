package com.example.javacodeagent.tool;

import com.example.javacodeagent.service.AgentTracerService;
import com.example.javacodeagent.service.StockFinancialService;
import com.example.javacodeagent.vo.StockFinancialVO;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class StockFinancialTool {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialTool.class);

    private final StockFinancialService stockFinancialService;
    private final AgentTracerService tracer;
    private final ToolExecutorSupport support;

    public StockFinancialTool(StockFinancialService stockFinancialService, AgentTracerService tracer,
                              ToolExecutorSupport support) {
        this.stockFinancialService = stockFinancialService;
        this.tracer = tracer;
        this.support = support;
    }

    @Tool("获取股票财务数据，包括营收、利润、ROE、毛利率、净利率、市盈率、市净率、资产负债率等")
    public String getFinancials(String secid) {
        return tracer.traceToolCall("getFinancials", secid, () -> {
            log.info("Tool 调用: getFinancials({})", secid);
            return support.execute("getFinancials", "secid", secid, () -> {
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
            }, e -> ToolErrors.secidError("getFinancials", secid, e, "获取财务数据"));
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
