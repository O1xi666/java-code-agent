package com.example.javacodeagent.service;

import com.example.javacodeagent.prompt.StockAnalysisPrompt;
import com.example.javacodeagent.config.CachePolicy;
import com.example.javacodeagent.rag.service.KnowledgeBaseService;
import com.example.javacodeagent.tool.StockCodeTool;
import com.example.javacodeagent.tool.StockFinancialTool;
import com.example.javacodeagent.tool.StockIndicatorTool;
import com.example.javacodeagent.tool.StockMarketTool;
import com.example.javacodeagent.tool.StockNewsTool;
import com.example.javacodeagent.util.DataCacheManager;
import com.example.javacodeagent.service.AgentTracerService;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 股票分析 Agent
 *
 * <p>在LLM分析之前，自动从知识库（KnowledgeBaseService）召回与该股票相关的投研知识片段，
 * 以【参考知识】形式嵌入用户输入，LLM回答时需基于这些知识并标注引用来源，
 * 从而降低幻觉，提高分析的可溯源性。
 */
@Component
public class StockAgent {

    private static final Logger log = LoggerFactory.getLogger(StockAgent.class);

    private final ChatLanguageModel chatLanguageModel;
    private final ContentRetriever contentRetriever;
    private final StockMarketTool stockMarketTool;
    private final StockFinancialTool stockFinancialTool;
    private final StockNewsTool stockNewsTool;
    private final StockIndicatorTool stockIndicatorTool;
    private final StockCodeTool stockCodeTool;
    private final ChatMemoryHolder memoryHolder;
    private final DataCacheManager cacheManager;
    private final AgentTracerService tracer;
    private final KnowledgeBaseService knowledgeBaseService;

    public StockAgent(
            ChatLanguageModel chatLanguageModel,
            ContentRetriever contentRetriever,
            StockMarketTool stockMarketTool,
            StockFinancialTool stockFinancialTool,
            AgentTracerService tracer,
            DataCacheManager cacheManager,
            StockNewsTool stockNewsTool,
            StockIndicatorTool stockIndicatorTool,
            StockCodeTool stockCodeTool,
            ChatMemoryHolder memoryHolder,
            KnowledgeBaseService knowledgeBaseService
    ) {
        this.chatLanguageModel = chatLanguageModel;
        this.contentRetriever = contentRetriever;
        this.stockMarketTool = stockMarketTool;
        this.stockFinancialTool = stockFinancialTool;
        this.stockNewsTool = stockNewsTool;
        log.info("Tools registered: market={}, financial={}, news={}, indicator={}, code={}",
                stockMarketTool != null, stockFinancialTool != null, stockNewsTool != null,
                stockIndicatorTool != null, stockCodeTool != null);
        this.stockIndicatorTool = stockIndicatorTool;
        this.stockCodeTool = stockCodeTool;
        this.memoryHolder = memoryHolder;
        this.cacheManager = cacheManager;
        this.tracer = tracer;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /**
     * 分析股票（含知识库召回增强）。
     *
     * <p>流程：
     * <ol>
     *   <li>从用户输入中提取股票代码</li>
     *   <li>从知识库召回相关片段 → 构建【参考知识】附录</li>
     *   <li>将知识附录拼接到用户输入前缀</li>
     *   <li>调用 LLM（LLM在 Prompt 中被要求基于参考知识作答并标注引用来源）</li>
     *   <li>返回带引用标注的分析结果</li>
     * </ol>
     */
    public String analyze(String userInput, String sessionId) {
        log.info("Agent 分析请求: sessionId={}, input={}", sessionId, userInput);

        String traceId = tracer.startTrace(sessionId, userInput);

        try {
            // 缓存检查
            String cacheKey = buildAnalyzeCacheKey(userInput);
            boolean useCache = cacheKey != null;
            if (useCache) {
                try {
                    if (memoryHolder.hasMemory(sessionId)) useCache = false;
                } catch (Exception ignored) {}
            }
            if (useCache) {
                String cached = cacheManager.getIfPresent(cacheKey);
                if (cached != null) {
                    log.info("LLM 分析缓存命中: key={}", cacheKey);
                    tracer.logOutput(traceId, cached.length() > 300 ? cached.substring(0, 300) + "..." : cached);
                    return cached;
                }
            }

            // ── 知识库召回增强 ────────────────────────────────────
            String enrichedInput = enrichWithKnowledge(userInput);

            ChatMemory chatMemory = memoryHolder.getChatMemory(sessionId);
            StockAnalysisInterface agent = AiServices.builder(StockAnalysisInterface.class)
                    .chatLanguageModel(chatLanguageModel)
                    .chatMemory(chatMemory)
                    .contentRetriever(contentRetriever)
                    .tools(stockMarketTool, stockFinancialTool, stockNewsTool, stockIndicatorTool, stockCodeTool)
                    .build();
            String result = agent.execute(enrichedInput);

            if (useCache) {
                cacheManager.put(cacheKey, result, CachePolicy.LLM_ANALYSIS);
                log.info("LLM 分析结果已缓存: key={}", cacheKey);
            }

            tracer.logOutput(traceId, result.length() > 500 ? result.substring(0, 500) + "..." : result);
            return result;
        } finally {
            tracer.endTrace(traceId);
        }
    }

    public Flux<String> analyzeStream(String userInput, String sessionId) {
        log.info("Agent 流式分析: sessionId={}, input={}", sessionId, userInput);
        return Mono.fromCallable(() -> analyze(userInput, sessionId)).flux().subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 用知识库召回结果增强用户输入。
     *
     * <p>策略：
     * <ul>
     *   <li>先用 stockCodeTool 提取股票代码</li>
     *   <li>若有代码，按代码过滤检索知识库（更精确）</li>
     *   <li>若无代码，按用户输入全文语义检索（模糊匹配）</li>
     *   <li>将召回结果格式化为【参考知识】附录拼接到用户输入前面</li>
     * </ul>
     */
    private String enrichWithKnowledge(String userInput) {
        String stockCode = stockCodeTool.findStockCode(userInput);
        String knowledgeContext;

        if (stockCode != null) {
            // 按股票代码检索（精确召回）
            knowledgeContext = knowledgeBaseService.buildKnowledgeContext(userInput, stockCode);
            log.info("知识库按代码召回: stockCode={}, contextLength={}", stockCode, knowledgeContext.length());
        } else {
            // 按全文语义检索（模糊召回）
            knowledgeContext = knowledgeBaseService.buildKnowledgeContext(userInput, null);
            log.info("知识库全文召回完成: contextLength={}", knowledgeContext.length());
        }

        if (knowledgeContext.isBlank()) {
            return userInput;
        }

        // 将【参考知识】附录拼接到用户输入前
        String prefix = knowledgeContext + "\n【用户问题】\n";
        String enriched = prefix + userInput;

        log.info("用户输入已增强: originalLen={}, enrichedLen={}", userInput.length(), enriched.length());
        return enriched;
    }

    @SystemMessage(StockAnalysisPrompt.SYSTEM_PROMPT)
    public interface StockAnalysisInterface {
        @UserMessage("{{it}}")
        String execute(String userInput);
    }

    /**
     * 从用户输入中提取股票代码，构建 LLM 缓存键。
     * 例如 "分析一下贵州茅台" → "llm:analysis:1.600519"
     */
    private String buildAnalyzeCacheKey(String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        String code = stockCodeTool.findStockCode(userInput);
        return code != null ? "llm:analysis:" + code : null;
    }
}
