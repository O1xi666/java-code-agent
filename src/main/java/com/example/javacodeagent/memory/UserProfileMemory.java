package com.example.javacodeagent.memory;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 三级记忆体系之「用户画像层」。
 *
 * <p>用结构化 KV 而不是自由文本保存用户长期偏好（投资风格、风险偏好、持有周期、
 * 关注赛道、自定义规则），这样既能精确注入 Prompt，也能做冲突校验。
 *
 * <p>冲突处理策略：偏好属于"用户业务规则"，模型不能擅自改写。当新提取到的取值
 * 与已存画像不一致时，不直接覆盖，而是写入待确认区并回传给模型，由模型先向用户
 * 确认；用户回复确认后才落库。这就是"偏好冲突校验 + 确认更新机制"，
 * 目的是避免模型把一次偶然表述固化成长期画像，导致业务规则被悄悄遗忘或篡改。
 */
@Service
public class UserProfileMemory {

    private static final Logger log = LoggerFactory.getLogger(UserProfileMemory.class);

    public static final String KEY_STYLE = "invest_style";
    public static final String KEY_RISK = "risk_preference";
    public static final String KEY_HORIZON = "holding_horizon";
    public static final String KEY_SECTORS = "focus_sectors";
    public static final String KEY_RULES = "custom_rules";

    /** 枚举型画像键：取值必须在白名单内，避免模型写入"稳健偏激进"这类不可判定的值 */
    private static final Map<String, Set<String>> ENUM_VALUES = Map.of(
            KEY_STYLE, Set.of("价值投资", "成长投资", "趋势交易", "短线博弈", "指数配置"),
            KEY_RISK, Set.of("保守", "稳健", "平衡", "进取", "激进"),
            KEY_HORIZON, Set.of("超短线", "短线", "中线", "长线"));

    /** 口语化别名到标准键的映射，降低模型抽取时的命名噪声 */
    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("风格", KEY_STYLE),
            Map.entry("投资风格", KEY_STYLE),
            Map.entry("style", KEY_STYLE),
            Map.entry("风险", KEY_RISK),
            Map.entry("风险偏好", KEY_RISK),
            Map.entry("risk", KEY_RISK),
            Map.entry("周期", KEY_HORIZON),
            Map.entry("持有周期", KEY_HORIZON),
            Map.entry("horizon", KEY_HORIZON),
            Map.entry("赛道", KEY_SECTORS),
            Map.entry("关注板块", KEY_SECTORS),
            Map.entry("规则", KEY_RULES),
            Map.entry("业务规则", KEY_RULES));

    private static final Duration PROFILE_TTL = Duration.ofDays(90);
    private static final Duration PENDING_TTL = Duration.ofHours(2);
    private static final int MAX_VALUE_LENGTH = 200;

    /** 一次画像写入的结果：applied=已落库，conflict=与已有画像冲突、待用户确认 */
    public record UpdateResult(boolean applied, boolean conflict, String key,
                               String oldValue, String newValue, String message) {

        static UpdateResult applied(String key, String value) {
            return new UpdateResult(true, false, key, null, value, "画像已更新：" + key + " = " + value);
        }

        static UpdateResult unchanged(String key, String value) {
            return new UpdateResult(false, false, key, value, value, "画像已是最新值：" + key + " = " + value);
        }

        static UpdateResult conflict(String key, String oldValue, String newValue) {
            return new UpdateResult(false, true, key, oldValue, newValue,
                    "检测到偏好冲突：" + key + " 原为「" + oldValue + "」，新提取到「" + newValue + "」，需用户确认后更新");
        }

        static UpdateResult rejected(String key, String message) {
            return new UpdateResult(false, false, key, null, null, message);
        }
    }

    public record PendingUpdate(String key, String value, String oldValue, long askedAt) {
    }

    private final MemoryStore store;

    public UserProfileMemory(MemoryStore store) {
        this.store = store;
    }

    public Map<String, String> load(String userId) {
        Map<String, String> map = MemoryJsonUtil.read(store.get(profileKey(userId)),
                new TypeReference<LinkedHashMap<String, String>>() {
                });
        return map == null ? new LinkedHashMap<>() : map;
    }

    /**
     * 写入一条画像。冲突时不覆盖，转为待确认。
     */
    public UpdateResult apply(String userId, String rawKey, String rawValue) {
        if (rawKey == null || rawKey.isBlank() || rawValue == null || rawValue.isBlank()) {
            return UpdateResult.rejected(rawKey, "画像键或值为空，忽略");
        }
        String key = normalizeKey(rawKey);
        String value = rawValue.trim();
        if (value.length() > MAX_VALUE_LENGTH) {
            return UpdateResult.rejected(key, "画像值超长（>" + MAX_VALUE_LENGTH + "），已忽略");
        }
        Set<String> allowed = ENUM_VALUES.get(key);
        if (allowed != null && !allowed.contains(value)) {
            String normalized = matchAllowed(value, allowed);
            if (normalized == null) {
                return UpdateResult.rejected(key, key + " 的取值「" + value + "」不在允许集合内，可选：" + allowed);
            }
            value = normalized;
        }

        Map<String, String> profile = load(userId);
        String existing = profile.get(key);
        if (existing == null) {
            profile.put(key, value);
            persist(userId, profile);
            log.info("用户画像写入: user={}, {}={}", userId, key, value);
            return UpdateResult.applied(key, value);
        }
        if (existing.equals(value)) {
            return UpdateResult.unchanged(key, value);
        }
        savePending(userId, new PendingUpdate(key, value, existing, System.currentTimeMillis()));
        log.info("用户画像冲突待确认: user={}, {} 由 {} 变更为 {}", userId, key, existing, value);
        return UpdateResult.conflict(key, existing, value);
    }

    public Optional<PendingUpdate> pending(String userId) {
        return Optional.ofNullable(MemoryJsonUtil.read(store.get(pendingKey(userId)), PendingUpdate.class));
    }

    /** 用户确认后，把待确认值真正落库 */
    public Optional<UpdateResult> confirm(String userId) {
        Optional<PendingUpdate> pending = pending(userId);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        PendingUpdate update = pending.get();
        Map<String, String> profile = load(userId);
        profile.put(update.key(), update.value());
        persist(userId, profile);
        store.remove(pendingKey(userId));
        log.info("用户画像确认更新: user={}, {}={}", userId, update.key(), update.value());
        return Optional.of(UpdateResult.applied(update.key(), update.value()));
    }

    /** 用户否认后丢弃待确认值，保留原画像 */
    public boolean reject(String userId) {
        Optional<PendingUpdate> pending = pending(userId);
        if (pending.isEmpty()) {
            return false;
        }
        store.remove(pendingKey(userId));
        log.info("用户画像变更被拒绝，保留原值: user={}, {}", userId, pending.get().key());
        return true;
    }

    /** 渲染成可直接拼进 Prompt 的区块；无画像时返回空串 */
    public String render(String userId) {
        Map<String, String> profile = load(userId);
        if (profile.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("【用户画像】（历史会话沉淀的长期偏好，仅在相关时参考）\n");
        appendIfPresent(sb, profile, KEY_STYLE, "投资风格");
        appendIfPresent(sb, profile, KEY_RISK, "风险偏好");
        appendIfPresent(sb, profile, KEY_HORIZON, "持有周期");
        appendIfPresent(sb, profile, KEY_SECTORS, "关注赛道");
        appendIfPresent(sb, profile, KEY_RULES, "其他规则");
        Optional<PendingUpdate> pending = pending(userId);
        if (pending.isPresent()) {
            PendingUpdate update = pending.get();
            sb.append("（待确认变更：").append(update.key()).append(" 由「").append(update.oldValue())
                    .append("」改为「").append(update.value()).append("」，请先向用户确认再采用）\n");
        }
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, Map<String, String> profile, String key, String label) {
        String value = profile.get(key);
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append("：").append(value).append('\n');
        }
    }

    private void persist(String userId, Map<String, String> profile) {
        store.put(profileKey(userId), MemoryJsonUtil.write(profile), PROFILE_TTL);
    }

    private void savePending(String userId, PendingUpdate update) {
        store.put(pendingKey(userId), MemoryJsonUtil.write(update), PENDING_TTL);
    }

    private static String normalizeKey(String rawKey) {
        String key = rawKey.trim();
        return KEY_ALIASES.getOrDefault(key, key);
    }

    /** 宽松匹配：允许"偏稳健""稳健型"这类表述落到白名单值上 */
    private static String matchAllowed(String value, Set<String> allowed) {
        for (String candidate : allowed) {
            if (value.contains(candidate)) return candidate;
        }
        return null;
    }

    private static String profileKey(String userId) {
        return "memory:profile:" + safeUser(userId);
    }

    private static String pendingKey(String userId) {
        return "memory:profile:pending:" + safeUser(userId);
    }

    private static String safeUser(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    /** 供 Prompt 抽取逻辑使用的白名单值查询 */
    public static List<String> allowedValues(String key) {
        Set<String> values = ENUM_VALUES.get(normalizeKey(key));
        return values == null ? List.of() : List.copyOf(values);
    }
}
