package com.example.javacodeagent.service;

import com.example.javacodeagent.util.HttpClientUtil;
import com.example.javacodeagent.config.CachePolicy;
import com.example.javacodeagent.util.DataCacheManager;
import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.vo.StockQuoteVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class StockMarketService {

    private static final String SINA_QUOTE = "http://hq.sinajs.cn/list=";
    private static final String SINA_KLINE = "http://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData.getKLineData";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final DataCacheManager cacheManager;

    public StockMarketService(DataCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    public StockQuoteVO getQuote(String secid) {
        secid = normalizeSecid(secid);
        final String sid = secid;
        String cacheKey = "stock:quote:" + secid;
        return cacheManager.getOrFetch(cacheKey, () -> fetchQuote(sid), CachePolicy.STOCK_QUOTE);
    }

    private StockQuoteVO fetchQuote(String secid) {
        String sinaCode = secid.startsWith("1.") ? "sh" + secid.substring(2) : "sz" + secid.substring(2);
        String response = HttpClientUtil.get(SINA_QUOTE + sinaCode);

        // 解析 CSV: var hq_str_sh600519="name,open,yclose,current,high,low,..."
        int start = response.indexOf('"') + 1;
        int end = response.lastIndexOf('"');
        if (start <= 0 || end <= start) throw new RuntimeException("解析行情数据失败，响应: " + response.substring(0, Math.min(100, response.length())));
        String[] fields = response.substring(start, end).split(",");
        if (fields.length < 10) throw new RuntimeException("行情数据格式异常");

        StockQuoteVO quote = new StockQuoteVO();
        quote.setName(fields[0]);
        quote.setCode(sinaCode.substring(2));
        double current = Double.parseDouble(fields[3]);
        double prevClose = Double.parseDouble(fields[2]);
        quote.setPrice(Math.round(current * 100.0) / 100.0);
        quote.setOpen(Math.round(Double.parseDouble(fields[1]) * 100.0) / 100.0);
        quote.setHigh(Math.round(Double.parseDouble(fields[4]) * 100.0) / 100.0);
        quote.setLow(Math.round(Double.parseDouble(fields[5]) * 100.0) / 100.0);
        quote.setChange(Math.round((current - prevClose) / prevClose * 10000.0) / 100.0);
        quote.setVolume(Long.parseLong(fields[8]) / 100);
        return quote;
    }

    public List<StockKLineVO> getKLine(String secid, int klt, int lmt) {
        secid = normalizeSecid(secid);
        String sinaCode = secid.startsWith("1.") ? "sh" + secid.substring(2) : "sz" + secid.substring(2);
        try {
            String url = SINA_KLINE + "?symbol=" + sinaCode + "&scale=" + klt + "&ma=no&datalen=" + lmt;
            String response = HttpClientUtil.get(url);
            JsonNode arr = MAPPER.readTree(response);
            if (arr == null || !arr.isArray()) return List.of();

            List<StockKLineVO> klineList = new ArrayList<>();
            for (JsonNode item : arr) {
                StockKLineVO vo = new StockKLineVO();
                vo.setDate(getStr(item, "day"));
                vo.setOpen(getDbl(item, "open"));
                vo.setClose(getDbl(item, "close"));
                vo.setHigh(getDbl(item, "high"));
                vo.setLow(getDbl(item, "low"));
                vo.setVolume((long) getDbl(item, "volume"));
                klineList.add(vo);
            }
            return klineList;
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<StockKLineVO> getDailyKLine(String secid, int days) {
        secid = normalizeSecid(secid);
        final String sid = secid;
        final int d = Math.min(days, 120);
        String cacheKey = "stock:kline:" + sid + ":" + d;
        return cacheManager.getOrFetch(cacheKey,
                () -> getKLine(sid, 240, d),
                CachePolicy.STOCK_KLINE);
    }

    public List<Double> calculateMA(List<StockKLineVO> klineList) {
        if (klineList == null || klineList.size() < 5) return List.of();
        int size = klineList.size();
        double s5 = 0, s10 = 0, s20 = 0, s60 = 0;
        for (int i = 0; i < size; i++) {
            double p = klineList.get(i).getClose();
            if (i < 60) s60 += p;
            if (i < 20) s20 += p;
            if (i < 10) s10 += p;
            if (i < 5) s5 += p;
        }
        List<Double> r = new ArrayList<>();
        r.add(Math.round(s5 / 5 * 100.0) / 100.0);
        if (size >= 10) r.add(Math.round(s10 / 10 * 100.0) / 100.0);
        if (size >= 20) r.add(Math.round(s20 / 20 * 100.0) / 100.0);
        if (size >= 60) r.add(Math.round(s60 / 60 * 100.0) / 100.0);
        return r;
    }

    private static String normalizeSecid(String secid) {
        if (secid == null) return "";
        if (secid.contains(".")) return secid;
        if (secid.matches("\\d{6}")) return secid.startsWith("6") ? "1." + secid : "0." + secid;
        return secid;
    }

    private double getDbl(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? 0.0 : v.asDouble();
    }
    private String getStr(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "" : v.asText();
    }
}
