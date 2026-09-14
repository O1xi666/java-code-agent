package com.example.javacodeagent.memory;

import com.example.javacodeagent.model.MemoryCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三级记忆体系之「历史结论层」。
 *
 * <p>解决两个真实问题：跨会话"串题"（把 A 股票的旧结论套到 B 股票上）和
 * "过时结论干扰"（一个月前的看多结论被当成今天的判断依据）。
 *
 * <p>治理手段有三层：
 * <ol>
 *   <li><b>写入准入</b>：只有"带四维评分 + 有工具观测支撑 + 本轮分析正常完成"的回答
 *       才允许沉淀，避免把寒暄、失败回答、幻觉内容写进结论库；</li>
 *   <li><b>失效标记</b>：按结论性质设置有效期（含行情/技术面信号的结论 24 小时失效，
 *       纯基本面结论 7 天失效），同一标的有新结论时旧结论立即置为 STALE；</li>
 *   <li><b>归档治理</b>：失效/被取代的结论移入归档区并限量保留，检索时只读 ACTIVE，
 *       既控制上下文用量，也保留可追溯的历史。</li>
 * </ol>
 */
@Service
public class ConclusionMemory {

    private static final Logger log = LoggerFactory.getLogger(ConclusionMemory.class);

    public enum Status {
        /** 有效，可注入上下文 */
        ACTIVE,
        /** 已失效，不再注入上下文 */
        STALE,
        /** 已归档，仅用于追溯 */
        ARCHIVED
    }

    /** 含行情/技术面信号的结论，时效性极强，当天有效 */
    private static final Duration TTL_PRICE_SENSITIVE = Duration.ofHours(24);
    /** 纯基本面结论变化慢，7 天有效 */
    private static final Duration TTL_DEFAULT = Duration.ofDays(7);

    private static final int MAX_ACTIVE = 20;
    private static final int MAX_ARCHIVE = 100;
    private static final int MIN_ANSWER_LENGTH = 60;
    private static final int SUMMARY_LENGTH = 240;

    private static final List<String> PRICE_SENSITIVE_MARKERS = List.of(
            "最新价", "技术面", "MACD", "RSI", "KDJ", "布林", "涨跌幅", "成交量");
    private static final List<String> ABORT_MARKERS = List.of(
            "分析已停止", "工具调用次数超过上限");

    private static final Pattern SCORE_PATTERN = Pattern.compile("【综合】\\s*得分[:：]?\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern DIRECTION_PATTERN = Pattern.compile("(看多|看空|中性)");
    private static final Pattern CONFIDENCE_PATTERN = Pattern.compile("信心[:：]\\s*(高|中|低)");

    /** 一条可复用的历史结论 */
    public static class Conclusion {
        private String id;
        private String stockCode;
        private String stockName;
        private String question;
        private String summary;
        private String score;
        private String direction;
        private String confidence;
        private boolean factChecked;
        private Status status = Status.ACTIVE;
        private String invalidReason;
        private long createdAt;
        private long expiresAt;

        public Conclusion() {
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }

        public String getStockCode() { return stockCode; }
        public void setStockCode(String stockCode) { this.stockCode = stockCode; }

        public String getStockName() { return stockName; }
        public void setStockName(String stockName) { this.stockName = stockName; }

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }

        public String getSummary() { return summary; }
        public void setSummary(String summary) { this.summary = summary; }

        public String getScore() { return score; }
        public void setScore(String score) { this.score = score; }

        public String getDirection() { return direction; }
        public void setDirection(String direction) { this.direction = direction; }

        public String getConfidence() { return confidence; }
        public void setConfidence(String confidence) { this.confidence = confidence; }

        public boolean isFactChecked() { return factChecked; }
        public void setFactChecked(boolean factChecked) { this.factChecked = factChecked; }

        public Status getStatus() { return status; }
        public void setStatus(Status status) { this.status = status; }

        public String getInvalidReason() { return invalidReason; }
        public void setInvalidReason(String invalidReason) { this.invalidReason = invalidReason; }

        public long getCreatedAt() { return createdAt; }
        public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

        public long getExpiresAt() { return expiresAt; }
        public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }

        public boolean isExpired(long now) {
            return expiresAt > 0 && now >= expiresAt;
        }
    }

    /** 写入准入结果，reason 会写进日志，便于解释"为什么这条回答没被沉淀" */
    public record Admission(boolean admitted, String reason) {

        static Admission reject(String reason) {
            return new Admission(false, reason);
        }

        static Admission accept() {
            return new Admission(true, "通过准入校验");
        }
    }

    private final MemoryCardStore store;

    public ConclusionMemory(MemoryCardStore store) {
        this.store = store;
    }

    /**
     * 写入准入校验：模型输出→可复用结论之间的一道闸门。
     */
    public Admission evaluateAdmission(String answer, boolean factChecked, int toolObservationCount) {
        if (answer == null || answer.isBlank()) {
            return Admission.reject("回答为空");
        }
        String text = answer.trim();
        if (text.length() < MIN_ANSWER_LENGTH) {
            return Admission.reject("回答过短（<" + MIN_ANSWER_LENGTH + " 字），不构成分析结论");
        }
        for (String marker : ABORT_MARKERS) {
            if (text.contains(marker)) {
                return Admission.reject("本轮分析未正常完成");
            }
        }
        if (!ImportanceScorer.looksLikeConclusion(text)) {
            return Admission.reject("缺少四维评分，不属于结论性输出");
        }
        if (toolObservationCount <= 0) {
            return Admission.reject("没有任何工具观测数据支撑，存在幻觉风险");
        }
        if (!factChecked) {
            // 事实校验关闭或未通过时仍允许沉淀，但标记 factChecked=false，供后续排查
            log.debug("结论未经事实一致性校验，仍按准入规则写入，标记 factChecked=false");
        }
        return Admission.accept();
    }

    /**
     * 写入一条结论：先过准入，再把同标的旧结论置为失效并归档。
     *
     * @return 落库的结论；未通过准入时返回 null
     */
    public Conclusion write(String userId, String stockCode, String stockName, String question,
                            String answer, boolean factChecked, int toolObservationCount) {
        Admission admission = evaluateAdmission(answer, factChecked, toolObservationCount);
        if (!admission.admitted()) {
            log.info("历史结论写入被拒绝: user={}, stock={}, 原因={}", userId, stockCode, admission.reason());
            return null;
        }

        long now = System.currentTimeMillis();
        Conclusion conclusion = new Conclusion();
        conclusion.setId(UUID.randomUUID().toString().substring(0, 8));
        conclusion.setStockCode(stockCode);
        conclusion.setStockName(stockName);
        conclusion.setQuestion(truncate(question, 120));
        conclusion.setSummary(buildSummary(answer));
        conclusion.setScore(extract(SCORE_PATTERN, answer));
        conclusion.setDirection(extract(DIRECTION_PATTERN, answer));
        conclusion.setConfidence(extract(CONFIDENCE_PATTERN, answer));
        conclusion.setFactChecked(factChecked);
        conclusion.setStatus(Status.ACTIVE);
        conclusion.setCreatedAt(now);
        conclusion.setExpiresAt(now + ttlFor(answer).toMillis());

        List<Conclusion> active = loadActive(userId);
        List<Conclusion> archive = loadArchive(userId);

        // 失效标记：同一标的最新的分析取代旧结论，直接移出上下文
        for (Conclusion old : new ArrayList<>(active)) {
            if (sameStock(old, conclusion)) {
                old.setStatus(Status.STALE);
                old.setInvalidReason("被同一标的最新的分析取代");
                archive.add(old);
                active.remove(old);
            }
        }
        active.add(conclusion);
        trimActive(active, archive);
        trimArchive(archive);

        persist(userId, active, archive);
        log.info("历史结论已写入: user={}, stock={}({}), score={}, direction={}, expiresAt={}",
                userId, stockName, stockCode, conclusion.getScore(), conclusion.getDirection(),
                conclusion.getExpiresAt());
        return conclusion;
    }

    /**
     * 读取可用结论：顺带把过期结论置为失效并归档（失效标记 + 归档治理）。
     *
     * @param stockCode 为空时返回全部有效结论
     */
    public List<Conclusion> loadUsable(String userId, String stockCode) {
        List<Conclusion> active = loadActive(userId);
        List<Conclusion> archive = loadArchive(userId);
        long now = System.currentTimeMillis();

        boolean changed = false;
        List<Conclusion> usable = new ArrayList<>();
        for (Conclusion conclusion : new ArrayList<>(active)) {
            if (conclusion.isExpired(now)) {
                conclusion.setStatus(Status.STALE);
                conclusion.setInvalidReason("结论超过有效期，已自动失效");
                archive.add(conclusion);
                active.remove(conclusion);
                changed = true;
            } else if (conclusion.getStatus() == Status.ACTIVE) {
                usable.add(conclusion);
            }
        }
        if (changed) {
            trimArchive(archive);
            persist(userId, active, archive);
        }
        if (stockCode == null || stockCode.isBlank()) {
            return usable;
        }
        return usable.stream().filter(c -> stockCode.equals(c.getStockCode())).toList();
    }

    public List<Conclusion> loadArchived(String userId) {
        return loadArchive(userId);
    }

    /** 渲染成 Prompt 区块；只注入同一标的的有效结论，从源头规避跨标的串题 */
    public String render(String userId, String stockCode) {
        List<Conclusion> usable = loadUsable(userId, stockCode);
        if (usable.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【历史结论】（同一标的的既往分析，若与本次数据冲突，请明确指出旧结论已失效并说明原因）\n");
        for (Conclusion conclusion : usable) {
            sb.append("- ").append(formatTime(conclusion.getCreatedAt())).append(' ')
                    .append(conclusion.getStockName() == null ? "" : conclusion.getStockName())
                    .append('(').append(conclusion.getStockCode()).append(')')
                    .append(" | 综合得分:").append(conclusion.getScore() == null ? "N/A" : conclusion.getScore())
                    .append(" | ").append(conclusion.getDirection() == null ? "未给出方向" : conclusion.getDirection())
                    .append(" 信心:").append(conclusion.getConfidence() == null ? "N/A" : conclusion.getConfidence())
                    .append(" | 事实校验:").append(conclusion.isFactChecked() ? "通过" : "未通过")
                    .append('\n').append("  摘要：").append(conclusion.getSummary()).append('\n');
        }
        return sb.toString();
    }

    /** 供诊断/自检使用：当前有效结论条数 */
    public int activeCount(String userId) {
        return loadActive(userId).size();
    }

    private Duration ttlFor(String answer) {
        for (String marker : PRICE_SENSITIVE_MARKERS) {
            if (answer.contains(marker)) {
                return TTL_PRICE_SENSITIVE;
            }
        }
        return TTL_DEFAULT;
    }

    private static boolean sameStock(Conclusion left, Conclusion right) {
        if (left.getStockCode() != null && right.getStockCode() != null) {
            return left.getStockCode().equals(right.getStockCode());
        }
        return left.getStockName() != null && left.getStockName().equals(right.getStockName());
    }

    private static void trimActive(List<Conclusion> active, List<Conclusion> archive) {
        while (active.size() > MAX_ACTIVE) {
            Conclusion oldest = active.remove(0);
            oldest.setStatus(Status.ARCHIVED);
            oldest.setInvalidReason("有效结论数量超限，最早的结论转入归档");
            archive.add(oldest);
        }
    }

    private static void trimArchive(List<Conclusion> archive) {
        while (archive.size() > MAX_ARCHIVE) {
            archive.remove(0);
        }
    }

    private List<Conclusion> loadActive(String userId) {
        return new ArrayList<>(store.findByStatus(safeUser(userId), Status.ACTIVE.name())
                .stream().map(ConclusionMemory::toConclusion).toList());
    }

    private List<Conclusion> loadArchive(String userId) {
        return new ArrayList<>(store.findNotStatus(safeUser(userId), Status.ACTIVE.name())
                .stream().map(ConclusionMemory::toConclusion).toList());
    }

    /**
     * 把 active + archive 两个列表同步到 memory_card 表:不在列表里的行删除,其余插入或更新。
     *
     * <p>这样表的最终状态完全由这两个列表决定,被 trim 掉的卡片会自动从表里消失,
     * 不需要在裁剪逻辑里额外调用删除。
     */
    private void persist(String userId, List<Conclusion> active, List<Conclusion> archive) {
        String owner = safeUser(userId);
        Map<String, MemoryCard> keep = new LinkedHashMap<>();
        for (Conclusion conclusion : active) {
            keep.put(conclusion.getId(), toCard(owner, conclusion));
        }
        for (Conclusion conclusion : archive) {
            keep.put(conclusion.getId(), toCard(owner, conclusion));
        }
        List<MemoryCard> obsolete = store.findAll(owner).stream()
                .filter(card -> !keep.containsKey(card.getId()))
                .toList();
        if (!obsolete.isEmpty()) {
            store.deleteAll(obsolete);
        }
        store.saveAll(new ArrayList<>(keep.values()));
    }

    private static MemoryCard toCard(String userId, Conclusion conclusion) {
        MemoryCard card = new MemoryCard();
        card.setId(conclusion.getId());
        card.setUserId(userId);
        card.setStockCode(conclusion.getStockCode());
        card.setStockName(conclusion.getStockName());
        card.setQuestion(conclusion.getQuestion());
        card.setSummary(conclusion.getSummary());
        card.setScore(conclusion.getScore());
        card.setDirection(conclusion.getDirection());
        card.setConfidence(conclusion.getConfidence());
        card.setFactChecked(conclusion.isFactChecked());
        card.setStatus(conclusion.getStatus().name());
        card.setInvalidReason(conclusion.getInvalidReason());
        card.setCreatedAt(conclusion.getCreatedAt());
        card.setExpiresAt(conclusion.getExpiresAt());
        return card;
    }

    private static Conclusion toConclusion(MemoryCard card) {
        Conclusion conclusion = new Conclusion();
        conclusion.setId(card.getId());
        conclusion.setStockCode(card.getStockCode());
        conclusion.setStockName(card.getStockName());
        conclusion.setQuestion(card.getQuestion());
        conclusion.setSummary(card.getSummary());
        conclusion.setScore(card.getScore());
        conclusion.setDirection(card.getDirection());
        conclusion.setConfidence(card.getConfidence());
        conclusion.setFactChecked(card.isFactChecked());
        conclusion.setStatus(Status.valueOf(card.getStatus()));
        conclusion.setInvalidReason(card.getInvalidReason());
        conclusion.setCreatedAt(card.getCreatedAt());
        conclusion.setExpiresAt(card.getExpiresAt());
        return conclusion;
    }

    private static String buildSummary(String answer) {
        String compact = answer.replaceAll("\\s+", " ").trim();
        int adviceIndex = compact.indexOf("建议");
        String summary = adviceIndex > 0 ? compact.substring(adviceIndex) : compact;
        return truncate(summary, SUMMARY_LENGTH);
    }

    private static String extract(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String truncate(String text, int maxLength) {
        if (text == null) return null;
        String trimmed = text.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength) + "...";
    }

    private static String formatTime(long epochMillis) {
        return java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis),
                java.time.ZoneId.systemDefault()).toLocalDate().toString();
    }

    private static String safeUser(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }
}
