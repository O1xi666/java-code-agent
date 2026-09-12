package com.example.javacodeagent.service;

import com.example.javacodeagent.vo.StockIndicatorVO;
import com.example.javacodeagent.vo.StockKLineVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

class StockIndicatorServiceTest {

    private StockIndicatorService indicatorService;

    @BeforeEach
    void setUp() {
        indicatorService = new StockIndicatorService();
    }

    private List<StockKLineVO> generateKLineData(int count, double basePrice, double trend) {
        List<StockKLineVO> klineList = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StockKLineVO kline = new StockKLineVO();
            double price = basePrice + trend * i + Math.sin(i * 0.5) * 2;
            kline.setDate("2024-01-" + String.format("%02d", (i % 31) + 1));
            kline.setOpen(price - 0.5);
            kline.setHigh(price + 1.0);
            kline.setLow(price - 1.0);
            kline.setClose(price);
            kline.setVolume(1000000L + (long)(Math.random() * 500000));
            klineList.add(kline);
        }
        return klineList;
    }

    @Test
    void testCalculateAll_WithSufficientData() {
        List<StockKLineVO> klineList = generateKLineData(65, 100.0, 0.5);
        StockIndicatorVO vo = indicatorService.calculateAll(klineList);
        assertNotNull(vo, "StockIndicatorVO should not be null");
        assertNotNull(vo.getMacdLine(), "MAC DIF line should not be null");
        assertFalse(vo.getMacdLine().isEmpty(), "MACD DIF line should not be empty");
        assertTrue(vo.getRsi6() >= 0 && vo.getRsi6() <= 100, "RSI should be 0-100, got: " + vo.getRsi6());
        assertNotNull(vo.getkValue(), "KDJ K should not be null");
        assertTrue(vo.getBollingerUpper() > vo.getBollingerLower(), "Upper band should exceed lower band");
        assertNotNull(vo.getMomentum(), "Momentum should not be null");
        assertNotNull(vo.getMacdSignal(), "MACD signal should not be null");
    }

    @Test
    void testCalculateAll_InsufficientData() {
        // 数据不足（< 34 根 K 线）时 calculateAll 不抛异常，而是返回空 VO，
        // 由调用方（MonitoringService / StockIndicatorTool）按 size 自行降级，
        // 这样上层可以统一处理"数据不足"而不用依赖异常控制流程。
        List<StockKLineVO> klineList = generateKLineData(10, 100.0, 0.0);
        StockIndicatorVO vo = indicatorService.calculateAll(klineList);
        assertNotNull(vo);
        assertNull(vo.getMacdLine(), "数据不足时不应计算 MACD");
        assertNull(vo.getRsi6(), "数据不足时不应计算 RSI");
        assertNull(vo.getkValue(), "数据不足时不应计算 KDJ");
    }

    @Test
    void testMACD_WithConstantPrices() {
        List<StockKLineVO> klineList = generateKLineData(60, 100.0, 0.0);
        StockIndicatorVO vo = indicatorService.calculateAll(klineList);
        double latestDif = vo.getMacdLine().get(vo.getMacdLine().size() - 1);
        assertTrue(Math.abs(latestDif) < 1.0, "DIF should be near 0: " + latestDif);
    }

    @Test
    void testMACD_WithUptrend() {
        List<StockKLineVO> klineList = generateKLineData(60, 100.0, 1.0);
        StockIndicatorVO vo = indicatorService.calculateAll(klineList);
        double latestDif = vo.getMacdLine().get(vo.getMacdLine().size() - 1);
        assertTrue(latestDif > 0, "DIF should be positive in uptrend: " + latestDif);
    }

    @Test
    void testRSI_Range() {
        List<StockKLineVO> upKline = generateKLineData(60, 100.0, 0.5);
        List<StockKLineVO> downKline = generateKLineData(60, 200.0, -0.5);
        StockIndicatorVO upVo = indicatorService.calculateAll(upKline);
        StockIndicatorVO downVo = indicatorService.calculateAll(downKline);
        assertTrue(upVo.getRsi6() >= 0 && upVo.getRsi6() <= 100);
        assertTrue(downVo.getRsi6() >= 0 && downVo.getRsi6() <= 100);
    }

    @Test
    void testBollingerBands_Logic() {
        List<StockKLineVO> klineList = generateKLineData(60, 100.0, 0.3);
        StockIndicatorVO vo = indicatorService.calculateAll(klineList);
        double upDiff = vo.getBollingerUpper() - vo.getBollingerMiddle();
        double lowDiff = vo.getBollingerMiddle() - vo.getBollingerLower();
        double ratio = Math.abs(upDiff - lowDiff) / Math.max(upDiff, lowDiff);
        assertTrue(ratio < 0.1, "Bollinger bands asymmetry too high: " + ratio);
    }
}
