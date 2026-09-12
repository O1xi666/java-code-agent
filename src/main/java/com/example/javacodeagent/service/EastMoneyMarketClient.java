package com.example.javacodeagent.service;

import com.example.javacodeagent.util.HttpClientUtil;
import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.vo.StockQuoteVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 东方财富行情客户端（作为新浪行情的自动降级数据源）
 *
 * WHY：新浪 hq.sinajs.cn / money.finance.sina.com.cn 是单点，一旦不可用
 * 行情与 K 线就整体失败。这里封装东方财富 push2 / push2his 接口，
 * 由 StockMarketService 在新浪失败时调用，保证“对接双财经数据源支持故障自动降级”的承诺成立。
 *
 * 设计要点：
 *   1. 纯静态解析方法（parseQuote / parseKLine）便于脱离网络做单元测试；
 *   2. 所有字段解析都是防御式的：字段缺失、值为 "-"（停牌）或格式异常时返回 null/跳过，
 *      而不是抛异常，避免上游接口改版导致整条链路崩溃；
 *   3. secid 沿用新浪归一化后的格式（1.=沪市，0.=深市），与东方财富市场号一致，无需二次转换。
 */
public class EastMoneyMarketClient {

    private static final Logger log = LoggerFactory.getLogger(EastMoneyMarketClient.class);

    private static final String QUOTE_URL = "https://push2.eastmoney.com/api/qt/stock/get";
    private static final String KLINE_URL = "https://push2his.eastmoney.com/api/qt/stock/kline/get";

    // 行情字段：f43=最新价 f44=最高 f45=最低 f46=今开 f47=成交量(手) f48=成交额
    //          f57=代码 f58=名称 f60=昨收 f169=涨跌额 f170=涨跌幅 f116=总市值 f162=市盈率 f167=市净率
    private static final String QUOTE_FIELDS = "f43,f44,f45,f46,f47,f48,f57,f58,f60,f169,f170,f116,f162,f167";

    private static final String KLINE_FIELDS1 = "f1,f2,f3,f4,f5,f6";
    private static final String KLINE_FIELDS2 = "f51,f52,f53,f54,f55,f56,f57,f58";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 拉取东方财富实时行情。
     *
     * @param secid 已归一化的 secid，形如 1.600519 / 0.000001
     * @return 行情 VO；data 为 null（secid 无效）时抛异常，交由 StockMarketService 判定为失败
     */
    public StockQuoteVO fetchQuote(String secid) {
        String url = QUOTE_URL + "?secid=" + toSecid(secid) + "&fltt=2&fields=" + QUOTE_FIELDS;
        String response = HttpClientUtil.get(url);
        StockQuoteVO quote = parseQuote(response);
        if (quote == null) {
            // data 为空视为失败，让调用方抛出聚合两个数据源的异常
            throw new RuntimeException("东方财富行情 data 为空（secid 可能无效）: " + secid);
        }
        return quote;
    }

    /**
     * 拉取东方财富 K 线。
     *
     * @param klt 新浪语义的周期（240=日 / 1200=周 / 7200=月），内部映射为东方财富 klt
     */
    public List<StockKLineVO> fetchKLine(String secid, int klt, int lmt) {
        String url = KLINE_URL + "?secid=" + toSecid(secid)
                + "&klt=" + mapKlt(klt) + "&fqt=1&lmt=" + lmt
                + "&fields1=" + KLINE_FIELDS1 + "&fields2=" + KLINE_FIELDS2;
        String response = HttpClientUtil.get(url);
        return parseKLine(response);
    }

    /**
     * 把新浪 scale 语义映射到东方财富 klt 语义。
     * WHY：两家的周期编码不同，klt 传错会拿到错误的周期数据。
     */
    static int mapKlt(int sinaKlt) {
        switch (sinaKlt) {
            case 240: return 101;   // 日 K
            case 1200: return 102;  // 周 K
            case 7200: return 103;  // 月 K
            default: return 101;
        }
    }

    /**
     * 解析行情 JSON（package-private static，便于纯单元测试，不触网）。
     *
     * 响应形如 {"data":{"f43":1500.0,"f57":"600519",...}}，data 为 null 表示 secid 无效，
     * 此时返回 null 而不是抛异常，保持“schema 变化优雅降级”。
     */
    static StockQuoteVO parseQuote(String json) {
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || data.isNull() || !data.isObject()) return null;

            StockQuoteVO quote = new StockQuoteVO();
            quote.setCode(getText(data, "f57"));
            quote.setName(getText(data, "f58"));
            setIfPresent(getDouble(data, "f43"), v -> quote.setPrice(round2(v)));
            setIfPresent(getDouble(data, "f46"), v -> quote.setOpen(round2(v)));
            setIfPresent(getDouble(data, "f44"), v -> quote.setHigh(round2(v)));
            setIfPresent(getDouble(data, "f45"), v -> quote.setLow(round2(v)));
            // fltt=2 时 f170 已是百分比数值，直接使用（新浪路径是自行计算涨跌幅）
            setIfPresent(getDouble(data, "f170"), v -> quote.setChange(round2(v)));
            // f47 单位是“手”，与新浪 (成交量股数 / 100) 之后的口径一致
            setIfPresent(getDouble(data, "f47"), v -> quote.setVolume(v.longValue()));
            setIfPresent(getDouble(data, "f48"), quote::setTurnover);
            setIfPresent(getDouble(data, "f116"), quote::setMarketCap);
            setIfPresent(getDouble(data, "f162"), quote::setPe);
            // f60=昨收、f167=市净率：StockQuoteVO 没有对应 setter，暂不落库（见实现说明）
            return quote;
        } catch (Exception e) {
            log.warn("东方财富行情 JSON 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 解析 K 线 JSON（package-private static，便于纯单元测试，不触网）。
     *
     * 响应形如 {"data":{"klines":["2026-08-07,open,close,high,low,volume,amount,amplitude"]}}；
     * klines 缺失/为空/单行字段不足时返回空列表，绝不抛异常。
     */
    static List<StockKLineVO> parseKLine(String json) {
        List<StockKLineVO> result = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root == null ? null : root.get("data");
            if (data == null || data.isNull()) return result;
            JsonNode klines = data.get("klines");
            if (klines == null || !klines.isArray() || klines.isEmpty()) return result;

            for (JsonNode item : klines) {
                String line = item == null ? null : item.asText("").trim();
                if (line == null || line.isEmpty()) continue;
                String[] f = line.split(",");
                // 字段顺序：date,open,close,high,low,volume,amount,amplitude
                if (f.length < 6) continue;
                Double open = parseDouble(f[1]);
                Double close = parseDouble(f[2]);
                Double high = parseDouble(f[3]);
                Double low = parseDouble(f[4]);
                Double volume = parseDouble(f[5]);
                if (open == null || close == null || high == null || low == null) continue;

                StockKLineVO vo = new StockKLineVO();
                vo.setDate(f[0]);
                vo.setOpen(open);
                vo.setClose(close);
                vo.setHigh(high);
                vo.setLow(low);
                // 东方财富 K 线成交量单位为“手”，新浪为“股”，乘 100 统一口径，保证降级前后指标语义一致
                vo.setVolume(volume == null ? 0L : (long) (volume * 100));
                result.add(vo);
            }
        } catch (Exception e) {
            log.warn("东方财富 K 线 JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
        return result;
    }

    /** secid 兜底归一化：外部若直接传入裸 6 位代码，按 6 开头沪市、其余深市补齐。 */
    private static String toSecid(String secid) {
        if (secid == null || secid.isBlank()) return "";
        if (secid.contains(".")) return secid;
        if (secid.matches("\\d{6}")) return secid.startsWith("6") ? "1." + secid : "0." + secid;
        return secid;
    }

    private static void setIfPresent(Double value, Consumer<Double> setter) {
        if (value != null) setter.accept(value);
    }

    private static Double getDouble(JsonNode node, String key) {
        JsonNode v = node.get(key);
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isNumber()) return v.asDouble();
        return parseDouble(v.asText(""));
    }

    private static Double parseDouble(String text) {
        if (text == null) return null;
        String t = text.trim();
        // 停牌等场景东方财富会返回 "-"，按缺失处理
        if (t.isEmpty() || "-".equals(t) || "--".equals(t)) return null;
        try {
            return Double.parseDouble(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String getText(JsonNode node, String key) {
        JsonNode v = node.get(key);
        return v == null || v.isNull() ? "" : v.asText("");
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}