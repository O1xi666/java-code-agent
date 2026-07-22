package com.example.javacodeagent.service;

import com.example.javacodeagent.util.HttpClientUtil;
import com.example.javacodeagent.config.CachePolicy;
import com.example.javacodeagent.util.DataCacheManager;
import com.example.javacodeagent.util.SeleniumDriver;
import com.example.javacodeagent.vo.StockFinancialVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票财务数据服务
 *
 * 数据获取优先级：
 *   1. Selenium 从东方财富财务摘要页面抓取（真实财报数据）
 *   2. HTTP 从 datacenter API 获取（JSON 格式，无需渲染）
 *   3. 两者均失败时返回空列表
 *
 * 注意：push2.eastmoney.com/api/qt/stock/get 返回的是行情数据（股价/股本），
 *       其中的 f43/f44/f47/f60 分别是股价/最高价/市盈率等，
 *       不能用于营收/利润/ROE。详见 通用知识库 中的规则说明。
 */
@Service
public class StockFinancialService {

    private static final Logger log = LoggerFactory.getLogger(StockFinancialService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataCacheManager cacheManager;
    private final SeleniumDriver seleniumDriver;

    public StockFinancialService(SeleniumDriver seleniumDriver, DataCacheManager cacheManager) {
        this.seleniumDriver = seleniumDriver;
        this.cacheManager = cacheManager;
    }

    public List<StockFinancialVO> getRecentFinancials(String secid) {
        if (secid == null || secid.isBlank()) return List.of();
        String cacheKey = "stock:fin:" + secid;
        return cacheManager.getOrFetch(cacheKey, () -> fetchFinancials(secid), CachePolicy.STOCK_FINANCIAL);
    }

    /**
     * 获取财务数据（主路径：Selenium → API 降级）
     *
     * 变更日志：Selenium 从降级路径改为主路径，因为行情 API 的 f43/f44 字段
     * 不是营收/利润，而是股价/最高价，会导致严重的财务数据失真。
     */
    private List<StockFinancialVO> fetchFinancials(String secid) {
        // 路径1：Selenium 从东方财富 HTML 页面抓取真实财务数据
        List<StockFinancialVO> seleniumResult = getFinancialsViaSelenium(secid);
        if (seleniumResult != null && !seleniumResult.isEmpty()) {
            return seleniumResult;
        }

        // 路径2：使用 HttpClient 直接请求财务数据 API（无需渲染）
        List<StockFinancialVO> httpResult = fetchViaHttp(secid);
        if (httpResult != null && !httpResult.isEmpty()) {
            return httpResult;
        }

        log.warn("所有财务数据获取方式均失败: {}", secid);
        return List.of();
    }

    /**
     * HTTP 降级路径：使用 East Money 数据中心 API
     * 相比 Selenium 更快，但接口可能随网站改版而变化。
     */
    private List<StockFinancialVO> fetchViaHttp(String secid) {
        try {
            String code = secid.contains(".") ? secid.split("\\.")[1] : secid;
            String prefix = secid.startsWith("1.") ? "SH" : "SZ";
            String secuCode = prefix + code;

            // 使用 East Money 数据中心 API 获取主要财务指标
            String url = "https://datacenter-web.eastmoney.com/api/data/v1/get"
                + "?reportName=RPT_F10_FINANCE_MAINFINADATA"
                + "&columns=SECUCODE,SECURITY_NAME_ABBR,REPORT_DATE,BASIC_EPS,WEIGHTAVG_ROE,OPERATE_INCOME,NET_PROFIT_ATSOPC"
                + "&filter=(SECUCODE=\"" + secuCode + "\")"
                + "&pageSize=1&sortTypes=-1&sortColumns=REPORT_DATE";

            String response = HttpClientUtil.get(url);
            JsonNode root = MAPPER.readTree(response);
            JsonNode result = root.get("result");
            if (result == null || !result.has("data") || result.get("data").isEmpty()) {
                return List.of();
            }
            JsonNode data = result.get("data").get(0);

            StockFinancialVO vo = new StockFinancialVO();
            vo.setYear("latest");
            vo.setReportType("年报");
            vo.setRevenue(getDbl(data, "OPERATE_INCOME"));
            vo.setProfit(getDbl(data, "NET_PROFIT_ATSOPC"));
            vo.setRoE(getDbl(data, "WEIGHTAVG_ROE"));
            // PE 需要单独查询行情接口
            vo.setPe(fetchPe(secid));

            return List.of(vo);
        } catch (Exception e) {
            log.warn("HTTP 财务数据获取失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 从行情接口获取市盈率（独立请求）
     */
    private Double fetchPe(String secid) {
        try {
            String url = "https://push2.eastmoney.com/api/qt/stock/get?secid=" + secid + "&fields=f43,f44,f47,f60";
            String response = HttpClientUtil.get(url);
            JsonNode root = MAPPER.readTree(response);
            JsonNode data = root.get("data");
            if (data == null) return null;
            return Math.round(getDbl(data, "f60") / 10000.0 * 100.0) / 100.0;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 使用 Selenium 从东方财富财务摘要页面抓取财务数据
     */
    private List<StockFinancialVO> getFinancialsViaSelenium(String secid) {
        try {
            String code = secid.contains(".") ? secid.split("\\.")[1] : secid;
            String prefix = secid.startsWith("1.") ? "SH" : "SZ";
            String url = "https://emweb.securities.eastmoney.com/PC_HSF10/FinanceSummary/Index?type=soft&code=" + prefix + code;

            String html = seleniumDriver.getContentAfterWaiting(url, "body", 8);
            if (html == null || html.isBlank()) return List.of();

            Document doc = Jsoup.parse(html);
            List<StockFinancialVO> list = new ArrayList<>();

            Elements tables = doc.select("table");
            for (Element table : tables) {
                Elements rows = table.select("tr");
                if (rows.size() < 2) continue;

                StockFinancialVO vo = new StockFinancialVO();
                vo.setYear("latest");
                vo.setReportType("年报");

                Elements cells = rows.get(0).select("td, th");
                if (cells.size() >= 2) {
                    vo.setRevenue(parseDouble(cells.get(1).text()));
                }
                if (rows.size() > 1) {
                    Elements cells2 = rows.get(1).select("td, th");
                    if (cells2.size() >= 2) {
                        vo.setProfit(parseDouble(cells2.get(1).text()));
                    }
                }
                if (vo.getRevenue() != null && vo.getRevenue() > 0) {
                    list.add(vo);
                    break;
                }
            }

            if (list.isEmpty()) {
                log.warn("Selenium 未从页面解析到财务数据, URL: {}", url);
            }
            return list;
        } catch (Exception e) {
            log.error("Selenium 抓取财务数据失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 尝试从文本中解析数字（万元单位转为元）
     */
    private Double parseDouble(String text) {
        if (text == null || text.isBlank() || text.equals("--")) return null;
        try {
            String cleaned = text.replaceAll("[^0-9.\\-]", "").trim();
            if (cleaned.isEmpty()) return null;
            return Double.parseDouble(cleaned) * 10000;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private double getDbl(JsonNode data, String key) {
        JsonNode v = data.get(key);
        return v == null || v.isNull() ? 0.0 : v.asDouble();
    }
}