package com.example.javacodeagent.controller;

import com.example.javacodeagent.service.StockAgent;
import com.example.javacodeagent.service.StockMarketService;
import com.example.javacodeagent.service.StockNewsService;
import com.example.javacodeagent.vo.StockQuoteVO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(StockController.class)
class StockControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private StockAgent stockAgent;

    @MockBean
    private StockMarketService stockMarketService;

    @MockBean
    private StockNewsService stockNewsService;

    @Test
    void testDailyReport_WithDefaultWatchlist() throws Exception {
        StockQuoteVO quote = new StockQuoteVO();
        quote.setCode("1.600519");
        quote.setName("贵州茅台");
        quote.setPrice(1750.5);
        quote.setChange(2.5);
        quote.setVolume(50000L);
        quote.setMarketCap(2000000000000.0);
        quote.setPe(30.5);

        when(stockMarketService.getQuote(anyString())).thenReturn(quote);
        when(stockNewsService.getLatestNews(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/api/stock/daily-report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStocks").value(8))
                .andExpect(jsonPath("$.date").isNotEmpty())
                .andExpect(jsonPath("$.stocks[0].name").value("贵州茅台"))
                .andExpect(jsonPath("$.stocks[0].price").value(1750.5))
                .andExpect(jsonPath("$.stocks[0].change").value(2.5));
    }

    @Test
    void testDailyReport_WithCustomCodes() throws Exception {
        StockQuoteVO quote = new StockQuoteVO();
        quote.setCode("1.600519");
        quote.setName("贵州茅台");
        quote.setPrice(1750.5);
        quote.setChange(-4.5);
        quote.setVolume(100000L);
        quote.setMarketCap(2000000000000.0);
        quote.setPe(30.0);when(stockMarketService.getQuote(anyString())).thenReturn(quote);
        when(stockNewsService.getLatestNews(anyString())).thenReturn(List.of());

        mockMvc.perform(post("/api/stock/daily-report")
                        .param("codes", "1.600519"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStocks").value(1))
                .andExpect(jsonPath("$.alerts").value(1));
    }

    @Test
    void testAnalyze() throws Exception {
        when(stockAgent.analyze(anyString(), anyString())).thenReturn("[分析报告]");

        mockMvc.perform(post("/api/stock/analyze")
                        .content("分析一下贵州茅台")
                        .header("Content-Type", "application/json"))
                .andExpect(status().isOk());
    }
}