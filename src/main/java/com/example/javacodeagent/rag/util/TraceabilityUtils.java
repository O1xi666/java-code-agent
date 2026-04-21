package com.example.javacodeagent.rag.util;

import com.example.javacodeagent.rag.service.HybridSearchService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 溯源工具：
 * <ul>
 *     <li>将混合检索结果封装为可直接发送给 Ollama Qwen3:8B 的 Prompt</li>
 *     <li>要求模型输出每条建议都附带【规范依据】与原文片段</li>
 *     <li>解析模型输出并回填对应原文元数据，实现可审计溯源</li>
 * </ul>
 */
public final class TraceabilityUtils {

    /**
     * 用于从模型输出中抽取建议段。
     * <p>格式约定：建议N: ...【规范依据】...【原文片段】...</p>
     */
    private static final Pattern SUGGESTION_BLOCK_PATTERN = Pattern.compile(
            "(?ms)(建议\\s*\\d+\\s*[:：].*?)(?=(?:\\n\\s*建议\\s*\\d+\\s*[:：])|\\z)"
    );

    /**
     * 抽取【规范依据】字段。
     */
    private static final Pattern BASIS_PATTERN = Pattern.compile("【规范依据】\\s*(.+?)(?=\\n|$)");

    /**
     * 抽取【原文片段】字段。
     */
    private static final Pattern EVIDENCE_PATTERN = Pattern.compile("【原文片段】\\s*(.+?)(?=\\n|$)");

    /**
     * 抽取【引用ID】字段（建议在 Prompt 里强制模型带上）。
     */
    private static final Pattern REF_ID_PATTERN = Pattern.compile("【引用ID】\\s*(.+?)(?=\\n|$)");

    private TraceabilityUtils() {
        // 工具类不允许实例化
    }

    /**
     * 构建适配 Ollama Qwen3:8B 的 RAG Prompt。
     * <p>
     * 该 Prompt 明确要求：
     * <ul>
     *     <li>仅基于给定检索证据回答，不允许编造规范</li>
     *     <li>每条建议必须带【规范依据】与【原文片段】</li>
     *     <li>推荐额外输出【引用ID】，便于程序化溯源</li>
     * </ul>
     *
     * @param userQuestion 用户问题
     * @param hits         混合检索结果
     * @return 可直接给模型的完整 Prompt 文本
     */
    public static String buildTraceablePrompt(
            String userQuestion,
            List<HybridSearchService.HybridSearchResult> hits
    ) {
        String safeQuestion = Objects.toString(userQuestion, "").trim();
        List<HybridSearchService.HybridSearchResult> safeHits = hits == null ? List.of() : hits;

        String evidenceSection = buildEvidenceSection(safeHits);

        return """
                你是资深Java代码审查专家，当前运行在 Ollama Qwen3:8B 模型上。
                你的任务：严格依据下方“检索证据”给出改进建议，不得引入证据之外的规范条款。

                【硬性输出规则】
                1) 每条建议必须包含以下字段：
                   - 建议N:
                   - 【规范依据】<简述依据条款>
                   - 【引用ID】<证据ID，如 REF-1>
                   - 【原文片段】<证据中的关键句，尽量原样引用>
                   - 【改进建议】<可执行的代码建议>
                2) 若证据不足，必须明确写“证据不足”，不要臆造规范。
                3) 建议数量控制在 3~5 条，优先高风险问题。
                4) 输出使用中文。

                【用户问题】
                %s

                【检索证据】
                %s

                请按“硬性输出规则”直接输出结果，不要输出额外解释。
                """.formatted(safeQuestion, evidenceSection);
    }

    /**
     * 解析模型输出并补齐溯源信息。
     * <p>
     * 解析策略：
     * <ol>
     *     <li>按“建议N”切分建议块</li>
     *     <li>抽取【规范依据】【原文片段】【引用ID】</li>
     *     <li>优先用引用ID映射检索结果；若失败则用片段匹配回退</li>
     * </ol>
     *
     * @param llmOutput 模型原始文本输出
     * @param hits      原始检索结果（用于回填 source/chunkId/tokenCount）
     * @return 可直接用于前端展示或审计落库的结构化结果
     */
    public static List<TraceableSuggestion> parseAndAttachTraceability(
            String llmOutput,
            List<HybridSearchService.HybridSearchResult> hits
    ) {
        if (llmOutput == null || llmOutput.isBlank()) {
            return List.of();
        }

        List<HybridSearchService.HybridSearchResult> safeHits = hits == null ? List.of() : hits;
        Map<String, HybridSearchService.HybridSearchResult> hitByRefId = buildRefIdMap(safeHits);

        List<TraceableSuggestion> suggestions = new ArrayList<>();
        Matcher blockMatcher = SUGGESTION_BLOCK_PATTERN.matcher(llmOutput);
        int index = 1;
        while (blockMatcher.find()) {
            String block = blockMatcher.group(1).trim();

            String basis = captureFirst(BASIS_PATTERN, block);
            String evidence = captureFirst(EVIDENCE_PATTERN, block);
            String refId = captureFirst(REF_ID_PATTERN, block);

            HybridSearchService.HybridSearchResult matched = matchEvidence(refId, evidence, hitByRefId, safeHits);

            suggestions.add(new TraceableSuggestion(
                    index++,
                    block,
                    basis,
                    evidence,
                    refId,
                    matched == null ? "" : matched.chunkId(),
                    matched == null ? "" : matched.source(),
                    matched == null ? 0 : matched.tokenCount(),
                    matched == null ? "" : matched.content()
            ));
        }

        return suggestions;
    }

    /**
     * 将解析后的建议再次拼成统一文本，便于日志/审计落库。
     */
    public static String renderTraceableOutput(List<TraceableSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "未解析到可溯源建议。";
        }

        StringJoiner joiner = new StringJoiner("\n\n");
        for (TraceableSuggestion s : suggestions) {
            joiner.add("""
                    建议%d:
                    【规范依据】%s
                    【引用ID】%s
                    【原文片段】%s
                    【溯源chunk_id】%s
                    【溯源source】%s
                    【溯源token数】%d
                    """.formatted(
                    s.index(),
                    blankAsFallback(s.basis(), "未提取"),
                    blankAsFallback(s.refId(), "未提取"),
                    blankAsFallback(s.evidence(), "未提取"),
                    blankAsFallback(s.chunkId(), "未匹配"),
                    blankAsFallback(s.source(), "未匹配"),
                    s.tokenCount()
            ));
        }
        return joiner.toString();
    }

    private static String buildEvidenceSection(List<HybridSearchService.HybridSearchResult> hits) {
        if (hits.isEmpty()) {
            return "无可用证据。";
        }

        StringJoiner joiner = new StringJoiner("\n\n");
        for (int i = 0; i < hits.size(); i++) {
            HybridSearchService.HybridSearchResult hit = hits.get(i);
            String refId = "REF-" + (i + 1);
            joiner.add("""
                    [%s]
                    chunk_id: %s
                    source: %s
                    token_count: %d
                    final_score: %.6f
                    text: %s
                    """.formatted(
                    refId,
                    safe(hit.chunkId()),
                    safe(hit.source()),
                    hit.tokenCount(),
                    hit.finalScore(),
                    shrinkText(safe(hit.content()), 500)
            ));
        }
        return joiner.toString();
    }

    private static Map<String, HybridSearchService.HybridSearchResult> buildRefIdMap(
            List<HybridSearchService.HybridSearchResult> hits
    ) {
        return java.util.stream.IntStream.range(0, hits.size())
                .boxed()
                .collect(Collectors.toMap(
                        i -> "REF-" + (i + 1),
                        hits::get,
                        (a, b) -> a
                ));
    }

    private static HybridSearchService.HybridSearchResult matchEvidence(
            String refId,
            String evidence,
            Map<String, HybridSearchService.HybridSearchResult> hitByRefId,
            List<HybridSearchService.HybridSearchResult> hits
    ) {
        if (!isBlank(refId)) {
            HybridSearchService.HybridSearchResult direct = hitByRefId.get(refId.trim().toUpperCase(Locale.ROOT));
            if (direct != null) {
                return direct;
            }
        }

        if (isBlank(evidence)) {
            return null;
        }

        String needle = evidence.trim();
        for (HybridSearchService.HybridSearchResult hit : hits) {
            if (safe(hit.content()).contains(needle)) {
                return hit;
            }
        }
        return null;
    }

    private static String captureFirst(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String shrinkText(String text, int maxLen) {
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "...";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankAsFallback(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    /**
     * 结构化可溯源建议。
     *
     * @param index              建议序号
     * @param rawSuggestionBlock 原始建议文本块
     * @param basis              提取的规范依据
     * @param evidence           提取的原文片段
     * @param refId              提取的引用ID（REF-n）
     * @param chunkId            匹配到的 chunk_id
     * @param source             匹配到的文档来源
     * @param tokenCount         匹配到的 token 数
     * @param sourceContent      匹配到的完整原文（便于回放/审计）
     */
    public record TraceableSuggestion(
            int index,
            String rawSuggestionBlock,
            String basis,
            String evidence,
            String refId,
            String chunkId,
            String source,
            int tokenCount,
            String sourceContent
    ) {
    }
}
