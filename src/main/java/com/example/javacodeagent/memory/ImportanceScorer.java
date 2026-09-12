package com.example.javacodeagent.memory;

import java.util.List;

/**
 * 会话记忆重要性打分器。
 *
 * <p>会话层不做全量回灌：先给每条消息打 0~10 的重要性分，同时把
 * 「工具重试 / 结构化报错 / 重复调用 / 中间态提示」这类无效交互标记为噪声，
 * 动态 Token 窗口只在有效消息之间分配预算，从而提升上下文有效信息密度。
 *
 * <p>打分是启发式规则而非再调一次模型：零额外延迟、结果可复现、规则可解释，
 * 便于面试时逐条说明阈值来源。
 */
public final class ImportanceScorer {

    private static final int MAX_IMPORTANCE = 10;

    /** 用户提问里出现这些词说明是明确的投研意图，予以加权 */
    private static final List<String> INTENT_KEYWORDS = List.of(
            "分析", "推荐", "建议", "估值", "买入", "卖出", "风险", "对比", "财报",
            "走势", "趋势", "值不值得", "能不能买", "该不该", "目标价", "持仓", "操作");

    /** 工具结构化错误 / 重试 / 重复调用的特征串，命中即判为无效交互 */
    private static final List<String> NOISE_MARKERS = List.of(
            "DUPLICATE_CALL",
            "\"retryable\"",
            "工具调用轮数超过上限",
            "工具重复调用被拦截");

    /** 无信息量的中间态文案 */
    private static final List<String> TRANSIENT_MARKERS = List.of(
            "加载中", "等待中", "正在分析", "请稍候");

    /** 结论性回答的特征，用于区分"分析报告"与"普通寒暄" */
    private static final List<String> CONCLUSION_MARKERS = List.of(
            "【综合】", "【基本面】", "【技术面】", "得分");

    /** 未完成的回答，需要保留但降权 */
    private static final List<String> INCOMPLETE_MARKERS = List.of(
            "分析已停止", "无法分析", "未获取到", "知识库中暂无");

    private ImportanceScorer() {
    }

    /**
     * 打分结果。
     *
     * @param importance 0~10，0 表示噪声
     * @param kind       记录类型
     * @param noise      是否属于无效交互
     * @param reason     打分依据，便于日志排查与面试解释
     */
    public record Scored(int importance, MemoryRecord.Kind kind, boolean noise, String reason) {
    }

    public static Scored score(String role, String content) {
        if (content == null || content.isBlank()) {
            return new Scored(0, MemoryRecord.Kind.NOISE, true, "空内容");
        }
        String text = content.trim();

        String noiseReason = detectNoise(text);
        if (noiseReason != null) {
            return new Scored(0, MemoryRecord.Kind.NOISE, true, noiseReason);
        }

        if ("user".equalsIgnoreCase(role)) {
            return scoreUserQuery(text);
        }
        if ("assistant".equalsIgnoreCase(role)) {
            return scoreAssistantAnswer(text);
        }
        if ("tool".equalsIgnoreCase(role)) {
            return new Scored(5, MemoryRecord.Kind.TOOL_OBSERVATION, false, "工具观测数据");
        }
        return new Scored(4, MemoryRecord.Kind.TOOL_OBSERVATION, false, "其他上下文");
    }

    /** 判断一段助手输出是否属于"结论性分析报告"，历史结论层写入准入会复用 */
    public static boolean looksLikeConclusion(String text) {
        if (text == null || text.isBlank()) return false;
        return containsAny(text, CONCLUSION_MARKERS);
    }

    private static Scored scoreUserQuery(String text) {
        if (text.length() < 2) {
            return new Scored(0, MemoryRecord.Kind.NOISE, true, "内容过短");
        }
        int importance = 7;
        String reason = "用户提问";
        if (containsAny(text, INTENT_KEYWORDS)) {
            importance += 2;
            reason += " + 明确投研意图";
        }
        if (text.length() >= 40) {
            importance += 1;
            reason += " + 信息量较大";
        }
        return new Scored(Math.min(importance, MAX_IMPORTANCE), MemoryRecord.Kind.USER_QUERY, false, reason);
    }

    private static Scored scoreAssistantAnswer(String text) {
        if (containsAny(text, INCOMPLETE_MARKERS)) {
            return new Scored(3, MemoryRecord.Kind.ASSISTANT_ANSWER, false, "未完成的回答，降权保留");
        }
        int importance = 7;
        String reason = "助手回答";
        if (containsAny(text, CONCLUSION_MARKERS)) {
            importance += 2;
            reason += " + 结论性输出";
        }
        if (text.length() >= 400) {
            importance += 1;
            reason += " + 内容详实";
        }
        return new Scored(Math.min(importance, MAX_IMPORTANCE), MemoryRecord.Kind.ASSISTANT_ANSWER, false, reason);
    }

    private static String detectNoise(String text) {
        if (containsAny(text, NOISE_MARKERS)) {
            return "工具错误/重复调用的无效交互";
        }
        if (containsAny(text, TRANSIENT_MARKERS)) {
            return "中间态提示，无有效信息";
        }
        return null;
    }

    private static boolean containsAny(String text, List<String> markers) {
        for (String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }
}
