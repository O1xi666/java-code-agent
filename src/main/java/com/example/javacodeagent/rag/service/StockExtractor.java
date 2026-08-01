package com.example.javacodeagent.rag.service;

import com.example.javacodeagent.tool.StockCodeTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用 LLM 从用户问题中抽取目标股票，输出 targetName / targetCode。
 * targetCode 统一归一化为东方财富 secid 格式，例如 1.600519 / 0.000001。
 */
@Component
public class StockExtractor {

    private static final Logger log = LoggerFactory.getLogger(StockExtractor.class);

    private static final String EXTRACT_PROMPT = """
            你是股票名称抽取器。从用户问题中抽取股票名称和标准化代码。
            要求：
            1. 只输出一个 JSON 对象，不要输出任何解释或代码块。
            2. JSON 格式：{"targetName":"贵州茅台","targetCode":"SH600519"}
            3. targetName 是股票中文名称；targetCode 是标准化代码，沪市以 SH 开头，深市以 SZ 开头。
            4. 问题里没有提到股票时，targetName 和 targetCode 都输出空字符串。
            5. 只有股票名称、不知道代码时，targetCode 输出空字符串。

            用户问题：
            """;

    private static final Pattern SH_PREFIX = Pattern.compile("^SH(\\d{6})$");
    private static final Pattern SZ_PREFIX = Pattern.compile("^SZ(\\d{6})$");
    private static final Pattern DOT_SUFFIX = Pattern.compile("^(\\d{6})\\.(SH|SZ)$");
    private static final Pattern SECID = Pattern.compile("^[01]\\.\\d{6}$");

    private final ChatLanguageModel chatLanguageModel;
    private final StockCodeTool stockCodeTool;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Cache<String, StockTarget> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    public StockExtractor(ChatLanguageModel chatLanguageModel, StockCodeTool stockCodeTool) {
        this.chatLanguageModel = chatLanguageModel;
        this.stockCodeTool = stockCodeTool;
    }

    /**
     * 抽取用户问题中的目标股票，同一查询 30 分钟内复用结果。
     */
    public StockTarget extract(String query) {
        if (query == null || query.isBlank()) {
            return StockTarget.empty();
        }
        StockTarget cached = cache.getIfPresent(query);
        if (cached != null) {
            return cached;
        }
        StockTarget target = doExtract(query);
        cache.put(query, target);
        return target;
    }

    private StockTarget doExtract(String query) {
        try {
            List<ChatMessage> messages = List.of(UserMessage.from(EXTRACT_PROMPT + query));
            Response<AiMessage> response = chatLanguageModel.generate(messages);
            String raw = response == null || response.content() == null ? "" : response.content().text();
            JsonNode root = objectMapper.readTree(extractJson(raw));
            JsonNode node = (root != null && root.isArray() && !root.isEmpty()) ? root.get(0) : root;

            String name = text(node, "targetName");
            String code = text(node, "targetCode");
            String normalized = normalizeCode(code);
            if (normalized.isBlank() && !name.isBlank()) {
                String resolved = stockCodeTool.findStockCode(name);
                if (resolved != null && !resolved.isBlank()) {
                    normalized = resolved;
                }
            }
            if (name.isBlank() && normalized.isBlank()) {
                return StockTarget.empty();
            }
            return new StockTarget(name, normalized);
        } catch (Exception e) {
            log.warn("LLM 股票抽取失败，回退到注册表扫描: {}", e.getMessage());
            String fallbackCode = stockCodeTool.findStockCode(query);
            return fallbackCode == null || fallbackCode.isBlank()
                    ? StockTarget.empty()
                    : new StockTarget("", fallbackCode);
        }
    }

    static String normalizeCode(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        Matcher sh = SH_PREFIX.matcher(s);
        if (sh.matches()) return "1." + sh.group(1);
        Matcher sz = SZ_PREFIX.matcher(s);
        if (sz.matches()) return "0." + sz.group(1);
        Matcher suffix = DOT_SUFFIX.matcher(s);
        if (suffix.matches()) {
            return "SH".equals(suffix.group(2)) ? "1." + suffix.group(1) : "0." + suffix.group(1);
        }
        if (s.matches("\\d{6}")) {
            return s.startsWith("6") ? "1." + s : "0." + s;
        }
        if (SECID.matcher(s).matches()) return s;
        return s;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) return "";
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return "{}";
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return "{}";
        return raw.substring(start, end + 1);
    }

    public record StockTarget(String targetName, String targetCode) {
        public static StockTarget empty() {
            return new StockTarget("", "");
        }

        public boolean isEmpty() {
            return (targetName == null || targetName.isBlank())
                    && (targetCode == null || targetCode.isBlank());
        }
    }
}
