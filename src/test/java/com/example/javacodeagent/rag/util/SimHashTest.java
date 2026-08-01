package com.example.javacodeagent.rag.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SimHashTest {

    private static final long ZERO_FP = 0L;

    @Test
    void testSameText_ShouldBeDuplicate() {
        String text = "国泰集团今日股价上涨3.5%，成交量放大至5日均量的2倍，主力资金净流入1.2亿元";
        long fp1 = SimHash.compute(text);
        long fp2 = SimHash.compute(text);
        assertEquals(fp1, fp2);
        assertTrue(SimHash.isDuplicate(fp1, fp2));
    }

    @Test
    void testEmptyText_ShouldReturnZero() {
        assertEquals(ZERO_FP, SimHash.compute(null));
        assertEquals(ZERO_FP, SimHash.compute(""));
        assertEquals(ZERO_FP, SimHash.compute("   "));
    }

    @Test
    void testSimilarTexts_ShouldDetectDuplicate() {
        String t1 = "国泰集团今日股价上涨3.5%，成交量放大至5日均量的2倍，主力资金净流入1.2亿元，MACD出现金叉信号，RSI指标处于强势区域";
        String t2 = "国泰集团今天股价涨幅3.5%，成交额达到5日均量的2倍，主力资金流入1.2亿，MACD金叉出现，RSI处于强势区域";
        long fp1 = SimHash.compute(t1);
        long fp2 = SimHash.compute(t2);
        int dist = SimHash.hammingDistance(fp1, fp2);
        assertTrue(SimHash.isDuplicate(fp1, fp2),
                "近义文本应判为重复, 汉明距离=" + dist);
    }

    @Test
    void testDifferentTopics_ShouldNotDetectDuplicate() {
        String t1 = "国泰集团今日股价上涨3.5%，成交量放大至5日均量的2倍，主力资金净流入1.2亿元";
        String t2 = "贵州茅台今日股价下跌2.1%，北向资金持续流出，白酒板块整体走弱，短线注意风险";
        long fp1 = SimHash.compute(t1);
        long fp2 = SimHash.compute(t2);
        int dist = SimHash.hammingDistance(fp1, fp2);
        assertFalse(SimHash.isDuplicate(fp1, fp2),
                "不同主题不应判为重复, 汉明距离=" + dist);
    }

    @Test
    void testHammingDistance_ExactMatch() {
        long fp = SimHash.compute("测试文本");
        assertEquals(0, SimHash.hammingDistance(fp, fp));
    }

    @Test
    void testHammingDistance_LongerSimilarText() {
        String t1 = "茅台今日股价上涨，MACD金叉，RSI强势，成交量放大，主力资金流入，建议持有";
        String t2 = "茅台今天股价上涨，MACD形成金叉，RSI处于强势，成交量大增，主力资金净流入，继续持有";
        long fp1 = SimHash.compute(t1);
        long fp2 = SimHash.compute(t2);
        int dist = SimHash.hammingDistance(fp1, fp2);
        assertTrue(dist <= 6,
                "更长近义文本应在阈值内, 汉明距离=" + dist);
    }
}
