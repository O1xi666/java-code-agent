package com.example.javacodeagent.rag.service;

import com.example.javacodeagent.tool.StockCodeTool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StockExtractorTest {

    @Test
    void normalizeCodeSupportsCommonFormats() {
        assertEquals("1.600519", StockExtractor.normalizeCode("SH600519"));
        assertEquals("0.000001", StockExtractor.normalizeCode("SZ000001"));
        assertEquals("1.600519", StockExtractor.normalizeCode("600519"));
        assertEquals("0.000858", StockExtractor.normalizeCode("000858.SZ"));
        assertEquals("1.600519", StockExtractor.normalizeCode("1.600519"));
    }

    @Test
    void extractsNameAndNormalizedCode() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        StockCodeTool codeTool = mock(StockCodeTool.class);
        when(model.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("{\"targetName\":\"贵州茅台\",\"targetCode\":\"SH600519\"}")));

        StockExtractor extractor = new StockExtractor(model, codeTool);
        StockExtractor.StockTarget target = extractor.extract("分析一下贵州茅台");

        assertEquals("贵州茅台", target.targetName());
        assertEquals("1.600519", target.targetCode());
    }

    @Test
    void resolvesCodeFromRegistryWhenLlmOmitsCode() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        StockCodeTool codeTool = mock(StockCodeTool.class);
        when(model.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("{\"targetName\":\"茅台\",\"targetCode\":\"\"}")));
        when(codeTool.findStockCode("茅台")).thenReturn("1.600519");

        StockExtractor extractor = new StockExtractor(model, codeTool);
        StockExtractor.StockTarget target = extractor.extract("分析一下茅台");

        assertEquals("1.600519", target.targetCode());
    }

    @Test
    void emptyWhenNoStockMentioned() {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        StockCodeTool codeTool = mock(StockCodeTool.class);
        when(model.generate(anyList())).thenReturn(
                Response.from(AiMessage.from("{\"targetName\":\"\",\"targetCode\":\"\"}")));

        StockExtractor extractor = new StockExtractor(model, codeTool);
        StockExtractor.StockTarget target = extractor.extract("介绍一下RAG");

        assertTrue(target.isEmpty());
    }
}
