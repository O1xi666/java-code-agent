package com.example.javacodeagent.service;

import com.example.javacodeagent.prompt.StockAnalysisPrompt;
import com.example.javacodeagent.config.CachePolicy;
import com.example.javacodeagent.config.ToolCallGuardModel;
import com.example.javacodeagent.config.ToolCallLimitExceededException;
import com.example.javacodeagent.rag.service.KnowledgeBaseService;
import com.example.javacodeagent.rag.service.StockExtractor;
import com.example.javacodeagent.tool.StockCodeTool;
import com.example.javacodeagent.tool.StockFinancialTool;
import com.example.javacodeagent.tool.StockIndicatorTool;
import com.example.javacodeagent.tool.StockMarketTool;
import com.example.javacodeagent.tool.StockNewsTool;
import com.example.javacodeagent.tool.ToolExecutorSupport;
import com.example.javacodeagent.util.DataCacheManager;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 股票分析 Agent
 *
 * <p>在 LLM 分析之前，自动从知识库（KnowledgeBaseService）召回与该股票相关的投研知识片段，
 * 以【参考知识】形式嵌入用户输入，LLM 回答时需基于这些知识并标注引用来源，
 * 从而降低幻觉，提高分析的可溯源性。
 *
 * <p><b>记忆架构（三级）</b>：由 {@link AgentMemoryService} 统一编排
 * <ul>
 *   <li>会话层：{@link SessionMemory}，Redis 留档 + 重要性打分 + 动态 Token 窗口裁剪，过滤无效交互</li>
 *   <li>用户画像层：{@code UserProfileMemory}，结构化 KV + 偏好冲突校验与确认更新</li>
 *   <li>历史结论层：{@code ConclusionMemory}，写入准入 + 失效标记 + 归档治理</li>
 * </ul>
 *
 * <p><b>工具调用</b>：LangChain4j AiServices 以 Function Calling 的方式驱动 5 个业务工具，
 * 形成"推理 → 调用工具 → 观察结果 → 继续推理"的多轮循环（ReAct 的工程化落地形态），
 * 支持单工具调用与多工具串行组合的自主决策；工具异常以结构化错误回传给模型引导修正重试，
 * 并由 {@link ToolCallGuardModel} 限制最大调用轮数，避免无限循环。
 */
@Component
public class StockAgent {

    private static final Logger log = LoggerFactory.getLogger(StockAgent.class);

    /**
     * 工作记忆的消息条数安全上限。
     *
     * <p>真正的上下文裁剪由三级记忆里的动态 Token 窗口完成，这里只是一道兜底闸门，
     * 防止异常情况下消息条数失控。
     */
    static final int INFERENCE_WINDOW = 40;

    private final ChatLanguageModel chatLanguageModel;
    private final ContentRetriever contentRetriever;
    private final StockMarketTool stockMarketTool;
    private final StockFinancialTool stockFinancialTool;
    private final StockNewsTool stockNewsTool;
    private final StockIndicatorTool stockIndicatorTool;
    private final StockCodeTool stockCodeTool;
    private final SessionMemory sessionMemory;
    private final DataCacheManager cacheManager;
    private final AgentTracerService tracer;
    private final KnowledgeBaseService knowledgeBaseService;
    private final StockExtractor stockExtractor;
    private final ToolCallGuardModel toolCallGuardModel;
    private final ToolExecutorSupport toolExecutorSupport;
    private final FactCheckService factCheckService;
    private final AgentMemoryService agentMemoryService;

    @Value("${agent.fact-check.enabled:true}")
    private boolean factCheckEnabled;

    @Value("${agent.fact-check.max-rounds:2}")
    private int factCheckMaxRounds;

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
            SessionMemory sessionMemory,
            KnowledgeBaseService knowledgeBaseService,
            StockExtractor stockExtractor,
            ToolCallGuardModel toolCallGuardModel,
            ToolExecutorSupport toolExecutorSupport,
            FactCheckService factCheckService,
            AgentMemoryService agentMemoryService
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
        this.sessionMemory = sessionMemory;
        this.cacheManager = cacheManager;
        this.tracer = tracer;
        this.knowledgeBaseService = knowledgeBaseService;
        this.stockExtractor = stockExtractor;
        this.toolCallGuardModel = toolCallGuardModel;
        this.toolExecutorSupport = toolExecutorSupport;
        this.factCheckService = factCheckService;
        this.agentMemoryService = agentMemoryService;
    }

    /**
     * 分析股票（含知识库召回增强 + 三级记忆）。
     *
     * <p>处理流程：
     * <ol>
     *   <li>用户画像层：处理偏好确认/否决，抽取新的长期偏好</li>
     *   <li>缓存检查（仅无历史会话的单轮查询才走 LLM 结果缓存）</li>
     *   <li>知识库召回相关片段</li>
     *   <li>三级记忆组装：会话窗口（动态 Token 预算）+ 用户画像 + 历史结论</li>
     *   <li>调用 LLM（AiServices 自动将新用户消息和回复加入工作记忆）</li>
     *   <li>事实一致性自校验，未通过则带修正指令重生成</li>
     *   <li>回写会话层与历史结论层</li>
     * </ol>
     */
    public String analyze(String userInput, String sessionId) {
        log.info("Agent 分析请求: sessionId={}, input={}", sessionId, userInput);

        // 个人项目里以会话 ID 作为用户标识；接入登录体系后替换为真实 userId 即可
        String userId = sessionId;
        String traceId = tracer.startTrace(sessionId, userInput);

        try {
            String stockCode = stockCodeTool.findStockCode(userInput);
            StockExtractor.StockTarget target = stockExtractor.extract(userInput);
            String stockName = target == null ? null : target.targetName();

            // ── 用户画像层：偏好确认/否决 + 新偏好抽取 ──
            String memorySignals = agentMemoryService.handleUserSignals(userId, userInput);

            // ── 缓存检查：有历史会话时结果依赖上下文，不能复用缓存 ──
            String cacheKey = stockCode == null ? null : "llm:analysis:" + stockCode;
            boolean useCache = cacheKey != null && !sessionMemory.hasSession(sessionId);
            if (useCache) {
                String cached = cacheManager.getIfPresent(cacheKey);
                if (cached != null) {
                    log.info("LLM 分析缓存命中: key={}", cacheKey);
                    tracer.logOutput(traceId, cached.length() > 300 ? cached.substring(0, 300) + "..." : cached);
                    return cached;
                }
            }

            // ── 知识库召回 ──
            EnrichmentResult enrichment = enrichWithKnowledge(userInput, stockCode, target);

            // ── 三级记忆组装 ──
            AgentMemoryService.MemoryContext memory =
                    agentMemoryService.buildContext(userId, sessionId, stockCode);
            String enrichedInput = buildEnrichedInput(memory, enrichment, userInput, memorySignals);

            MessageWindowChatMemory workingMemory = MessageWindowChatMemory.builder()
                    .maxMessages(INFERENCE_WINDOW)
                    .id(sessionId)
                    .build();
            for (ChatMessage msg : memory.chatMessages()) {
                workingMemory.add(msg);
            }

            // ── 构建 AI Service 并调用 ──
            StockAnalysisInterface agent = AiServices.builder(StockAnalysisInterface.class)
                    .chatLanguageModel(chatLanguageModel)
                    .chatMemory(workingMemory)
                    .contentRetriever(contentRetriever)
                    .tools(stockMarketTool, stockFinancialTool, stockNewsTool, stockIndicatorTool, stockCodeTool)
                    .build();

            toolCallGuardModel.beginRequest();
            toolExecutorSupport.beginRequest();
            try {
                AnalysisResult analysisResult =
                        executeWithFactCheck(agent, enrichedInput, userInput, enrichment.knowledgeContext());
                String result = analysisResult.answer();

                // ── 回写三级记忆：会话层存"用户原始问题"而非增强后的输入 ──
                // 增强输入里包含知识库全文，若原样入库会在后续每轮重复膨胀上下文
                agentMemoryService.recordTurn(userId, sessionId, userInput, result,
                        analysisResult.factChecked(), tracer.getToolObservations().size(),
                        stockCode, stockName);

                if (useCache) {
                    cacheManager.put(cacheKey, result, CachePolicy.LLM_ANALYSIS);
                    log.info("LLM 分析结果已缓存: key={}", cacheKey);
                }

                tracer.logOutput(traceId, result.length() > 500 ? result.substring(0, 500) + "..." : result);
                return result;
            } catch (ToolCallLimitExceededException e) {
                log.warn("工具调用轮数超限: {}", e.getMessage());
                tracer.logOutput(traceId, "工具调用轮数超过上限，已停止");
                return "分析已停止：工具调用次数超过上限，请缩小问题范围后重试。";
            } finally {
                toolCallGuardModel.endRequest();
                toolExecutorSupport.endRequest();
            }
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
     *   <li>有股票代码时按代码过滤检索知识库（更精确）</li>
     *   <li>无代码时按用户输入全文语义检索（模糊匹配）</li>
     *   <li>召回结果格式化为【参考知识】附件，由 {@link #buildEnrichedInput} 统一拼接</li>
     * </ul>
     */
    private EnrichmentResult enrichWithKnowledge(String userInput, String stockCode,
                                                 StockExtractor.StockTarget target) {
        String knowledgeContext = stockCode != null
                ? knowledgeBaseService.buildKnowledgeContext(userInput, stockCode, target)
                : knowledgeBaseService.buildKnowledgeContext(userInput, null, target);
        log.info("知识库召回完成: stockCode={}, contextLength={}", stockCode, knowledgeContext.length());
        return new EnrichmentResult(knowledgeContext);
    }

    /**
     * 组装最终送入模型的用户输入：三级记忆区块 → 参考知识 → 记忆信号 → 用户问题。
     */
    private String buildEnrichedInput(AgentMemoryService.MemoryContext memory, EnrichmentResult enrichment,
                                      String userInput, String memorySignals) {
        StringBuilder sb = new StringBuilder();
        String memoryBlocks = memory.renderBlocks();
        if (!memoryBlocks.isBlank()) {
            sb.append(memoryBlocks);
        }
        if (!enrichment.knowledgeContext().isBlank()) {
            sb.append(enrichment.knowledgeContext());
        }
        if (memorySignals != null && !memorySignals.isBlank()) {
            sb.append(memorySignals).append('\n');
        }
        sb.append("【用户问题】\n").append(userInput);

        String enriched = sb.toString();
        log.info("用户输入已增强: originalLen={}, enrichedLen={}, 会话窗口={}条/{}token, 过滤无效交互={}条",
                userInput.length(), enriched.length(), memory.windowMessages(),
                memory.windowTokens(), memory.filteredNoise());
        return enriched;
    }

    /**
     * 生成 + 事实一致性自校验，未通过则带修正指令重新生成。
     */
    private AnalysisResult executeWithFactCheck(StockAnalysisInterface agent, String enrichedInput,
                                                String originalInput, String knowledgeContext) {
        String fixInstructions = "";
        String draft = null;
        for (int round = 1; round <= factCheckMaxRounds; round++) {
            String prompt = fixInstructions == null || fixInstructions.isBlank()
                    ? enrichedInput
                    : enrichedInput + "\n\n【修正指令】\n" + fixInstructions;
            draft = agent.execute(prompt);

            if (!factCheckEnabled) {
                return new AnalysisResult(draft, false);
            }

            List<String> observations = tracer.getToolObservations().stream()
                    .map(o -> o.tool() + "(" + o.arguments() + ") => " + o.result())
                    .toList();
            FactCheckService.FactCheckReport report = factCheckService.check(
                    originalInput, knowledgeContext, observations, draft);

            if (report.passed()) {
                return new AnalysisResult(draft, true);
            }
            log.warn("事实校验未通过（第{}轮）: {}", round, report.issues());
            fixInstructions = report.fixInstructions();
        }
        return new AnalysisResult(draft == null ? enrichedInput : draft, false);
    }

    @SystemMessage(StockAnalysisPrompt.SYSTEM_PROMPT)
    public interface StockAnalysisInterface {
        @UserMessage("{{it}}")
        String execute(String userInput);
    }

    /** 本轮回答 + 是否通过事实一致性校验（用于历史结论层的可信度标记） */
    private record AnalysisResult(String answer, boolean factChecked) {
    }

    private record EnrichmentResult(String knowledgeContext) {
    }
}
