package com.example.javacodeagent.service;

import com.example.javacodeagent.memory.ConclusionMemory;
import com.example.javacodeagent.memory.DynamicTokenWindow;
import com.example.javacodeagent.memory.MemoryRecord;
import com.example.javacodeagent.memory.UserProfileMemory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 三级记忆体系编排入口。
 *
 * <p>三层分工：
 * <ul>
 *   <li><b>会话层</b> {@link SessionMemory}：全量留档 + 重要性打分，按动态 Token 窗口裁剪，
 *       过滤工具重试/报错等无效交互，解决长对话上下文溢出；</li>
 *   <li><b>用户画像层</b> {@link UserProfileMemory}：结构化 KV 保存长期偏好，
 *       冲突时先确认再更新，避免模型擅自改写用户的业务规则；</li>
 *   <li><b>历史结论层</b> {@link ConclusionMemory}：写入准入 + 失效标记 + 归档治理，
 *       只把"同标的、仍在有效期"的结论注入上下文，规避跨会话串题与过时结论干扰。</li>
 * </ul>
 */
@Service
public class AgentMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AgentMemoryService.class);

    /** 用户确认/否决画像变更的口语化表达 */
    private static final List<String> CONFIRM_WORDS = List.of(
            "确认", "是的", "对的", "同意", "没错", "可以更新", "按这个来");
    private static final List<String> REJECT_WORDS = List.of(
            "不用", "不要", "取消", "算了", "维持原样", "保持原样", "别改");

    /** 只有出现偏好类表述才认为用户在陈述自己的画像，避免把行情描述误判成偏好 */
    private static final List<String> PREFERENCE_CUES = List.of(
            "偏好", "倾向", "风格", "习惯", "属于", "喜欢", "打算", "计划", "主要做", "平时", "通常");
    private static final Pattern FIRST_PERSON_STYLE = Pattern.compile("我(是|主要|一般|平时|通常)");

    private static final Pattern SECTOR_PATTERN = Pattern.compile(
            "(?:关注|看好|聚焦|重仓)\\s*([\\u4e00-\\u9fa5A-Za-z0-9、,，]{2,24}?)\\s*(?:板块|行业|赛道|方向)");
    private static final Pattern RULE_PATTERN = Pattern.compile("(?:记住|牢记)\\s*[：:，,]?\\s*(.{4,80})");

    private final SessionMemory sessionMemory;
    private final UserProfileMemory profileMemory;
    private final ConclusionMemory conclusionMemory;
    private final int tokenBudget;
    private final int maxRecords;

    public AgentMemoryService(SessionMemory sessionMemory,
                              UserProfileMemory profileMemory,
                              ConclusionMemory conclusionMemory,
                              @Value("${agent.memory.token-budget:2400}") int tokenBudget,
                              @Value("${agent.memory.max-records:30}") int maxRecords) {
        this.sessionMemory = sessionMemory;
        this.profileMemory = profileMemory;
        this.conclusionMemory = conclusionMemory;
        this.tokenBudget = tokenBudget;
        this.maxRecords = maxRecords;
    }

    /**
     * 三级记忆的组装结果：会话窗口（带 token 统计）+ 画像区块 + 历史结论区块。
     *
     * @param filteredNoise 被过滤掉的无效交互条数，用于日志与面试演示
     */
    public record MemoryContext(List<ChatMessage> chatMessages, String profileBlock, String conclusionBlock,
                                int windowTokens, int filteredNoise, int windowMessages) {

        /** 拼接画像与历史结论，返回可直接嵌入用户输入的区块 */
        public String renderBlocks() {
            StringBuilder sb = new StringBuilder();
            if (profileBlock != null && !profileBlock.isBlank()) {
                sb.append(profileBlock);
            }
            if (conclusionBlock != null && !conclusionBlock.isBlank()) {
                sb.append(conclusionBlock);
            }
            return sb.toString();
        }
    }

    /**
     * 构建本轮推理需要的记忆上下文。
     */
    public MemoryContext buildContext(String userId, String sessionId, String stockCode) {
        List<MemoryRecord> records = sessionMemory.load(sessionId);
        DynamicTokenWindow.Result window = DynamicTokenWindow.select(records, tokenBudget, maxRecords);

        List<ChatMessage> messages = new ArrayList<>();
        for (MemoryRecord record : window.selected()) {
            ChatMessage message = toChatMessage(record);
            if (message != null) {
                messages.add(message);
            }
        }

        String profileBlock = profileMemory.render(userId);
        String conclusionBlock = conclusionMemory.render(userId, stockCode);

        log.info("三级记忆组装: session={}, 留档 {} 条 → 会话窗口 {} 条/{} token, 过滤无效交互 {} 条, 超预算丢弃 {} 条",
                sessionId, records.size(), messages.size(), window.usedTokens(),
                window.droppedNoise(), window.droppedByBudget());

        return new MemoryContext(messages, profileBlock, conclusionBlock, window.usedTokens(),
                window.droppedNoise(), messages.size());
    }

    /**
     * 处理用户输入里的画像信号：确认/否决待定变更，或抽取新的偏好。
     *
     * @return 需要回传给模型的提示（画像更新/冲突），无信号时返回 null
     */
    public String handleUserSignals(String userId, String userInput) {
        if (userInput == null || userInput.isBlank()) return null;
        List<String> notes = new ArrayList<>();

        Optional<UserProfileMemory.PendingUpdate> pending = profileMemory.pending(userId);
        if (pending.isPresent()) {
            if (containsAny(userInput, CONFIRM_WORDS)) {
                profileMemory.confirm(userId)
                        .ifPresent(result -> notes.add("【记忆更新】用户已确认：" + result.message()));
            } else if (containsAny(userInput, REJECT_WORDS)) {
                UserProfileMemory.PendingUpdate update = pending.get();
                if (profileMemory.reject(userId)) {
                    notes.add("【记忆更新】用户已否决：" + update.key() + " 保持为「" + update.oldValue() + "」");
                }
            }
        }

        for (String[] candidate : extractProfileCandidates(userInput)) {
            UserProfileMemory.UpdateResult result = profileMemory.apply(userId, candidate[0], candidate[1]);
            if (result.applied()) {
                notes.add("【记忆更新】" + result.message());
            } else if (result.conflict()) {
                notes.add("【记忆冲突】" + result.message()
                        + "。请在回答开头用一句话向用户确认是否更新，不要自行改写已确认的画像。");
            }
        }
        return notes.isEmpty() ? null : String.join("\n", notes);
    }

    /**
     * 一轮分析结束后的记忆写入：会话层落两条（提问 + 回答），历史结论层按准入规则沉淀。
     *
     * @param userInput  用户原始问题（不是拼接了知识库/画像的增强输入，避免上下文重复膨胀）
     */
    public void recordTurn(String userId, String sessionId, String userInput, String answer,
                           boolean factChecked, int toolObservationCount,
                           String stockCode, String stockName) {
        MemoryRecord userRecord = sessionMemory.record(sessionId, "user", userInput);
        MemoryRecord answerRecord = sessionMemory.record(sessionId, "assistant", answer);
        log.info("会话层写入完成: 用户消息重要度={}, 回答重要度={}, token={}/{}",
                userRecord.getImportance(), answerRecord.getImportance(),
                userRecord.getTokens(), answerRecord.getTokens());

        if (stockCode == null || stockCode.isBlank()) {
            log.info("本轮未识别出明确标的，跳过历史结论沉淀");
            return;
        }
        conclusionMemory.write(userId, stockCode, stockName, userInput, answer,
                factChecked, toolObservationCount);
    }

    /** 供诊断接口使用：历史结论层当前有效条数 */
    public int activeConclusionCount(String userId) {
        return conclusionMemory.activeCount(userId);
    }

    private List<String[]> extractProfileCandidates(String text) {
        List<String[]> candidates = new ArrayList<>();
        String[] clauses = text.split("[。；;！!？?\\n]");

        for (String key : List.of(UserProfileMemory.KEY_STYLE, UserProfileMemory.KEY_RISK,
                UserProfileMemory.KEY_HORIZON)) {
            for (String value : UserProfileMemory.allowedValues(key)) {
                if (!text.contains(value)) continue;
                if (!hasPreferenceCue(clauseContaining(clauses, value, text))) break;
                candidates.add(new String[]{key, value});
                break;
            }
        }

        Matcher sector = SECTOR_PATTERN.matcher(text);
        if (sector.find()) {
            candidates.add(new String[]{UserProfileMemory.KEY_SECTORS,
                    sector.group(1).replaceAll("[、,，]", "/")});
        }
        Matcher rule = RULE_PATTERN.matcher(text);
        if (rule.find()) {
            candidates.add(new String[]{UserProfileMemory.KEY_RULES, rule.group(1).trim()});
        }
        return candidates;
    }

    private static String clauseContaining(String[] clauses, String value, String fallback) {
        for (String clause : clauses) {
            if (clause.contains(value)) return clause;
        }
        return fallback;
    }

    private static boolean hasPreferenceCue(String clause) {
        if (clause == null) return false;
        if (containsAny(clause, PREFERENCE_CUES)) return true;
        return FIRST_PERSON_STYLE.matcher(clause).find();
    }

    private static ChatMessage toChatMessage(MemoryRecord record) {
        if (record.getKind() == MemoryRecord.Kind.USER_QUERY) {
            return UserMessage.from(record.getContent());
        }
        if (record.getKind() == MemoryRecord.Kind.ASSISTANT_ANSWER) {
            return AiMessage.from(record.getContent());
        }
        // 工具观测类记录不进对话窗口：对话历史里混入工具输出会破坏 user/assistant 交替结构
        return null;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) return true;
        }
        return false;
    }
}
