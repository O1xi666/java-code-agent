package com.example.javacodeagent.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 动态 Token 窗口。
 *
 * <p>替代"固定取最近 N 条"的静态窗口：按 token 预算选消息，并遵循三条规则，
 * <ol>
 *   <li>噪声（工具报错/重试/重复调用/中间态）直接丢弃，不占预算；</li>
 *   <li>重要性 ≥ 8 的消息优先占用预算，其余按时间由新到旧补位；</li>
 *   <li>"最近一条用户提问"是锚点，即使超预算也必须保留，否则模型会丢失当前意图。</li>
 * </ol>
 * 长对话场景下，这套规则比固定条数窗口能塞进更多有效信息，同时避免上下文溢出。
 */
public final class DynamicTokenWindow {

    /** 高重要性阈值，与 ImportanceScorer 的"结论性输出/明确投研意图"分值对齐 */
    private static final int HIGH_IMPORTANCE = 8;

    /**
     * @param selected        按时间升序排列、可直接送入模型的消息
     * @param usedTokens      实际占用 token 数
     * @param droppedNoise    被过滤掉的无效交互条数
     * @param droppedByBudget 因预算不足被丢弃条数
     */
    public record Result(List<MemoryRecord> selected, int usedTokens, int droppedNoise, int droppedByBudget) {
    }

    private DynamicTokenWindow() {
    }

    public static Result select(List<MemoryRecord> records, int tokenBudget, int maxRecords) {
        List<MemoryRecord> usable = new ArrayList<>();
        int droppedNoise = 0;
        if (records != null) {
            for (MemoryRecord record : records) {
                if (record == null || record.getContent() == null || record.getContent().isBlank()) {
                    droppedNoise++;
                    continue;
                }
                if (record.isNoise() || record.getKind() == MemoryRecord.Kind.NOISE) {
                    droppedNoise++;
                    continue;
                }
                usable.add(record);
            }
        }
        usable.sort(Comparator.comparingLong(MemoryRecord::getCreatedAt));

        MemoryRecord anchor = lastUserQuery(usable);
        Set<String> pickedIds = new LinkedHashSet<>();
        int usedTokens = 0;

        // 锚点优先落座，保证当前意图不丢
        if (anchor != null) {
            pickedIds.add(anchor.getId());
            usedTokens += tokenCost(anchor);
        }

        // 第一轮：高重要性消息
        usedTokens = fill(usable, HIGH_IMPORTANCE, Integer.MAX_VALUE, pickedIds, usedTokens, tokenBudget);
        // 第二轮：其余消息按时间由新到旧补位
        usedTokens = fill(usable, 0, HIGH_IMPORTANCE - 1, pickedIds, usedTokens, tokenBudget);

        List<MemoryRecord> selected = new ArrayList<>();
        for (MemoryRecord record : usable) {
            if (pickedIds.contains(record.getId())) {
                selected.add(record);
            }
        }
        selected.sort(Comparator.comparingLong(MemoryRecord::getCreatedAt));

        int droppedByBudget = 0;
        if (maxRecords > 0 && selected.size() > maxRecords) {
            List<MemoryRecord> trimmed = new ArrayList<>();
            int keepFrom = selected.size() - maxRecords;
            for (int i = 0; i < selected.size(); i++) {
                boolean isAnchor = anchor != null && anchor.getId().equals(selected.get(i).getId());
                if (i < keepFrom && !isAnchor) {
                    droppedByBudget++;
                } else {
                    trimmed.add(selected.get(i));
                }
            }
            selected = trimmed;
        }

        return new Result(selected, usedTokens, droppedNoise, droppedByBudget);
    }

    /**
     * 在 [minImportance, maxImportance] 区间内按时间由新到旧填充，直到预算用尽。
     */
    private static int fill(List<MemoryRecord> usable, int minImportance, int maxImportance,
                            Set<String> pickedIds, int usedTokens, int tokenBudget) {
        for (int i = usable.size() - 1; i >= 0; i--) {
            MemoryRecord record = usable.get(i);
            if (pickedIds.contains(record.getId())) continue;
            int importance = record.getImportance();
            if (importance < minImportance || importance > maxImportance) continue;
            if (usedTokens >= tokenBudget) continue;

            int cost = tokenCost(record);
            if (usedTokens + cost > tokenBudget) continue;
            pickedIds.add(record.getId());
            usedTokens += cost;
        }
        return usedTokens;
    }

    private static int tokenCost(MemoryRecord record) {
        return record.getTokens() > 0 ? record.getTokens() : MemoryTokenizer.count(record.getContent());
    }

    private static MemoryRecord lastUserQuery(List<MemoryRecord> usable) {
        MemoryRecord found = null;
        for (MemoryRecord record : usable) {
            if (record.getKind() == MemoryRecord.Kind.USER_QUERY) {
                found = record;
            }
        }
        return found;
    }
}
