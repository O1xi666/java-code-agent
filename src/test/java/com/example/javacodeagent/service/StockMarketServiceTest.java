package com.example.javacodeagent.service;

import com.example.javacodeagent.vo.StockKLineVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

class StockMarketServiceTest {

    private StockMarketService stockMarketService;

    @BeforeEach
    void setUp() {
        stockMarketService = new StockMarketService(null);
    }

    private List<StockKLineVO> createKLineData(int count, double price) {
        List<StockKLineVO> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StockKLineVO k = new StockKLineVO();
            k.setDate("2024-01-" + String.format("%02d", i + 1));
            k.setOpen(price);
            k.setClose(price);
            k.setHigh(price);
            k.setLow(price);
            k.setVolume(1000000L);
            list.add(k);
        }
        return list;
    }

    @Test
    void testCalculateMA_WithEnoughData() {
        List<StockKLineVO> klineList = createKLineData(60, 100.0);
        List<Double> maList = stockMarketService.calculateMA(klineList);
        assertNotNull(maList);
        assertEquals(4, maList.size());
        for (Double ma : maList) {
            assertEquals(100.0, ma, 0.01, "All MAs should be 100.0");
        }
    }

    @Test
    void testCalculateMA_With10DataPoints() {
        List<StockKLineVO> klineList = createKLineData(10, 100.0);
        List<Double> maList = stockMarketService.calculateMA(klineList);
        assertNotNull(maList);
        assertTrue(maList.size() >= 1 && maList.size() <= 2);
    }

    @Test
    void testCalculateMA_WithInsufficientData() {
        List<StockKLineVO> klineList = createKLineData(3, 100.0);
        List<Double> maList = stockMarketService.calculateMA(klineList);
        assertNotNull(maList);
        assertTrue(maList.isEmpty()); // MA5 needs 5+ points
    }

    @Test
    void testCalculateMA_WithEmptyList() {
        List<StockKLineVO> klineList = new ArrayList<>();
        List<Double> maList = stockMarketService.calculateMA(klineList);
        assertNotNull(maList);
        assertTrue(maList.isEmpty());
    }
}
