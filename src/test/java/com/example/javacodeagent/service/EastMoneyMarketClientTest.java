package com.example.javacodeagent.service;

import com.example.javacodeagent.vo.StockKLineVO;
import com.example.javacodeagent.vo.StockQuoteVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

/**
 * EastMoneyMarketClient 解析逻辑纯单元测试（不触网）
 *
 * 覆盖：正常行情/K线解析、data 为 null、klines 为空这几类真实会遇到的边界，
 * 确保东方财富接口改版或 secid 无效时是"优雅降级"而不是抛异常。
 */
class EastMoneyMarketClientTest {

    // 真实结构样例：贵州茅台（secid=1.600519），价格为 fltt=2 的十进制值
    private static final String QUOTE_JSON = "{"
            + "\"rc\":0,\"rt\":4,\"svr\":1,"
            + "\"data\":{"
            + "\"f43\":1500.0,\"f44\":1520.5,\"f45\":1490.0,\"f46\":1495.0,"
            + "\"f47\":123456,\"f48\":1.85E9,"
            + "\"f57\":\"600519\",\"f58\":\"贵州茅台\",\"f60\":1498.0,"
            + "\"f116\":1.885E12,\"f162\":25.3,\"f167\":8.1,\"f169\":2.0,\"f170\":0.13"
            + "}}";

    private static final String KLINE_JSON = "{"
            + "\"rc\":0,\"data\":{\"code\":\"600519\",\"klines\":["
            + "\"2026-08-05,1490.00,1495.00,1500.00,1485.00,35000,5.2E9,1.5\","
            + "\"2026-08-06,1495.00,1498.00,1505.00,1492.00,32000,4.8E9,1.2\","
            + "\"2026-08-07,1500.00,1510.00,1515.00,1498.00,41000,6.1E9,1.7\""
            + "]}}";

    @Test
    void testParseQuote_FromCannedSample() {
        StockQuoteVO quote = EastMoneyMarketClient.parseQuote(QUOTE_JSON);
        assertNotNull(quote);
        assertEquals("600519", quote.getCode());
        assertEquals("贵州茅台", quote.getName());
        assertEquals(1500.0, quote.getPrice(), 0.001);
        assertEquals(1495.0, quote.getOpen(), 0.001);
        assertEquals(1520.5, quote.getHigh(), 0.001);
        assertEquals(1490.0, quote.getLow(), 0.001);
        assertEquals(0.13, quote.getChange(), 0.001);
        assertEquals(123456L, quote.getVolume().longValue());
        assertEquals(1.885E12, quote.getMarketCap(), 1.0);
        assertEquals(25.3, quote.getPe(), 0.001);
        assertEquals(1.85E9, quote.getTurnover(), 1.0);
    }

    @Test
    void testParseQuote_NullDataReturnsNullAndDoesNotThrow() {
        // secid 无效时东方财富会返回 data:null，此处必须优雅降级而非抛异常
        assertNull(EastMoneyMarketClient.parseQuote("{\"rc\":0,\"data\":null}"));
        assertNull(EastMoneyMarketClient.parseQuote("{}"));
        // 脏数据（非 JSON）同样不应抛出
        assertNull(EastMoneyMarketClient.parseQuote("not-a-json"));
    }

    @Test
    void testParseKLine_FromCannedSample() {
        List<StockKLineVO> list = EastMoneyMarketClient.parseKLine(KLINE_JSON);
        assertEquals(3, list.size());

        StockKLineVO first = list.get(0);
        assertEquals("2026-08-05", first.getDate());
        assertEquals(1490.0, first.getOpen(), 0.001);
        assertEquals(1495.0, first.getClose(), 0.001);
        assertEquals(1500.0, first.getHigh(), 0.001);
        assertEquals(1485.0, first.getLow(), 0.001);
        // 东方财富 K 线成交量单位为"手"，统一乘 100 换算为"股"，与新浪口径一致
        assertEquals(35000L * 100, first.getVolume().longValue());

        StockKLineVO last = list.get(2);
        assertEquals("2026-08-07", last.getDate());
        assertEquals(1510.0, last.getClose(), 0.001);
    }

    @Test
    void testParseKLine_EmptyOrMissingKlinesDoesNotThrow() {
        assertTrue(EastMoneyMarketClient.parseKLine("{\"rc\":0,\"data\":{\"klines\":[]}}").isEmpty());
        assertTrue(EastMoneyMarketClient.parseKLine("{\"rc\":0,\"data\":null}").isEmpty());
        assertTrue(EastMoneyMarketClient.parseKLine("{}").isEmpty());
        assertTrue(EastMoneyMarketClient.parseKLine("bad-json").isEmpty());
    }

    @Test
    void testMapKlt_SinaToEastMoneyScale() {
        assertEquals(101, EastMoneyMarketClient.mapKlt(240));   // 日 K
        assertEquals(102, EastMoneyMarketClient.mapKlt(1200));  // 周 K
        assertEquals(103, EastMoneyMarketClient.mapKlt(7200));  // 月 K
        assertEquals(101, EastMoneyMarketClient.mapKlt(9999));  // 未知周期默认日 K
    }
}