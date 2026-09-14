package com.example.javacodeagent.memory;

import com.example.javacodeagent.service.AgentMemoryService;
import com.example.javacodeagent.service.SessionMemory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三级记忆体系单元测试：重要性打分、动态 Token 窗口、画像冲突校验、历史结论治理。
 *
 * <p>全部用例都不依赖 Redis（{@code new MemoryStore(null)} 会自动走进程内兜底），
 * 也不依赖 LLM，可在 CI 里稳定运行。
 */
class MemorySystemTest {

    /** 项目里工具失败时真实回传的结构化错误观察 */
    private static final String TOOL_ERROR_JSON =
            "{\"tool\":\"getQuote\",\"code\":\"INVALID_SECID\",\"retryable\":true,\"maxAttempts\":3}";

    /** 一份包含四维评分的完整分析结论 */
    private static final String VALID_ANSWER =
            "【基本面】得分:80 理由:ROE 长期稳定，毛利率 91%，数据来源:财务工具。"
                    + "【技术面】得分:72 理由:均线多头排列，MACD 金叉，最新价 1750.2 元。"
                    + "【综合】得分:78 看多 信心:中。建议逢低分批建仓，注意仓位控制与止盈止损。";

    @Test
    void scorerTreatsToolErrorsAndDuplicateCallsAsNoise() {
        ImportanceScorer.Scored toolError = ImportanceScorer.score("tool", TOOL_ERROR_JSON);
        assertTrue(toolError.noise(), "工具结构化报错应判为噪声");
        assertEquals(0, toolError.importance());
        assertEquals(MemoryRecord.Kind.NOISE, toolError.kind());

        ImportanceScorer.Scored duplicate =
                ImportanceScorer.score("tool", "{\"code\":\"DUPLICATE_CALL\",\"message\":\"重复调用\"}");
        assertTrue(duplicate.noise(), "重复调用应判为噪声");

        ImportanceScorer.Scored blank = ImportanceScorer.score("user", "   ");
        assertTrue(blank.noise(), "空内容应判为噪声");
    }

    @Test
    void scorerRewardsIntentfulUserQueryAndConclusionOutput() {
        ImportanceScorer.Scored query =
                ImportanceScorer.score("user", "分析一下贵州茅台，现在值不值得买入？");
        assertFalse(query.noise());
        assertEquals(MemoryRecord.Kind.USER_QUERY, query.kind());
        assertTrue(query.importance() >= 9, "明确投研意图应获得高分，实际=" + query.importance());

        ImportanceScorer.Scored answer = ImportanceScorer.score("assistant", VALID_ANSWER);
        assertTrue(answer.importance() >= 9, "结论性回答应获得高分，实际=" + answer.importance());
        assertTrue(ImportanceScorer.looksLikeConclusion(VALID_ANSWER));
        assertFalse(ImportanceScorer.looksLikeConclusion("好的，还有什么可以帮您？"));
    }

    @Test
    void windowFiltersNoiseAndKeepsChronologicalOrder() {
        List<MemoryRecord> records = new ArrayList<>();
        records.add(record(MemoryRecord.Kind.USER_QUERY, "分析贵州茅台", 9, 10, false, 1));
        records.add(record(MemoryRecord.Kind.NOISE, TOOL_ERROR_JSON, 0, 10, true, 2));
        records.add(record(MemoryRecord.Kind.ASSISTANT_ANSWER, VALID_ANSWER, 9, 40, false, 3));

        DynamicTokenWindow.Result result = DynamicTokenWindow.select(records, 1000, 30);

        assertEquals(2, result.selected().size(), "噪声不应进入上下文");
        assertEquals(1, result.droppedNoise());
        assertEquals(50, result.usedTokens());
        assertTrue(result.selected().stream().noneMatch(MemoryRecord::isNoise));
        assertEquals(MemoryRecord.Kind.USER_QUERY, result.selected().get(0).getKind());
    }

    @Test
    void windowPrioritizesHighImportanceWithinBudget() {
        List<MemoryRecord> records = new ArrayList<>();
        // 锚点：最近一条用户提问，占 5 token
        records.add(record(MemoryRecord.Kind.USER_QUERY, "分析茅台", 9, 5, false, 1));
        // 较早但高重要性的结论，占 20 token
        records.add(record(MemoryRecord.Kind.ASSISTANT_ANSWER, "高分结论", 9, 20, false, 2));
        // 较新但低重要性的观测，占 20 token
        records.add(record(MemoryRecord.Kind.TOOL_OBSERVATION, "普通观测", 5, 20, false, 3));

        DynamicTokenWindow.Result result = DynamicTokenWindow.select(records, 25, 30);

        List<String> ids = result.selected().stream().map(MemoryRecord::getId).toList();
        assertTrue(ids.contains("r1"), "用户提问是锚点必须保留");
        assertTrue(ids.contains("r2"), "预算内应优先保留高重要性消息");
        assertFalse(ids.contains("r3"), "预算不足时应先丢弃低重要性消息");
        assertEquals(25, result.usedTokens());
    }

    @Test
    void windowKeepsAnchorEvenWhenBudgetIsExhausted() {
        List<MemoryRecord> records = new ArrayList<>();
        records.add(record(MemoryRecord.Kind.ASSISTANT_ANSWER, "很久以前的回答", 7, 500, false, 1));
        records.add(record(MemoryRecord.Kind.USER_QUERY, "现在分析茅台", 9, 500, false, 2));

        DynamicTokenWindow.Result result = DynamicTokenWindow.select(records, 10, 30);

        assertEquals(1, result.selected().size(), "预算极紧时只保留锚点");
        assertEquals(MemoryRecord.Kind.USER_QUERY, result.selected().get(0).getKind());
    }

    @Test
    void profileConflictRequiresExplicitConfirmation() {
        UserProfileMemory profile = new UserProfileMemory(new MemoryStore(null));

        assertTrue(profile.apply("u1", UserProfileMemory.KEY_RISK, "稳健").applied());

        UserProfileMemory.UpdateResult conflict = profile.apply("u1", UserProfileMemory.KEY_RISK, "激进");
        assertTrue(conflict.conflict(), "与已有画像冲突时不能直接覆盖");
        assertEquals("稳健", profile.load("u1").get(UserProfileMemory.KEY_RISK), "确认前必须保留原值");
        assertTrue(profile.pending("u1").isPresent());

        assertTrue(profile.confirm("u1").isPresent());
        assertEquals("激进", profile.load("u1").get(UserProfileMemory.KEY_RISK));
        assertTrue(profile.pending("u1").isEmpty(), "确认后待确认区应清空");

        assertTrue(profile.render("u1").contains("风险偏好：激进"));
    }

    @Test
    void profileRejectsValuesOutsideWhitelist() {
        UserProfileMemory profile = new UserProfileMemory(new MemoryStore(null));

        // "非常激进" 含白名单值 "激进" → 规范化为白名单取值
        assertTrue(profile.apply("u1", UserProfileMemory.KEY_RISK, "非常激进").applied());
        assertEquals("激进", profile.load("u1").get(UserProfileMemory.KEY_RISK));

        UserProfileMemory.UpdateResult invalid =
                profile.apply("u1", UserProfileMemory.KEY_STYLE, "随便买买");
        assertFalse(invalid.applied(), "不在白名单内的取值应被拒绝");
        assertFalse(profile.load("u1").containsKey(UserProfileMemory.KEY_STYLE));
    }

    @Test
    void agentMemoryOnlyExtractsProfileFromPreferenceStatements() {
        MemoryStore store = new MemoryStore(null);
        UserProfileMemory profile = new UserProfileMemory(store);
        AgentMemoryService service = new AgentMemoryService(new SessionMemory(store), profile,
                new ConclusionMemory(new InMemoryMemoryCardStore()), 2400, 30);

        assertNull(service.handleUserSignals("u1", "分析一下贵州茅台"),
                "普通个股问题不应被误判成偏好");
        assertNull(service.handleUserSignals("u1", "稳健的股票有哪些"),
                "缺少偏好类表述时不应写入画像");

        assertNotNull(service.handleUserSignals("u1", "我偏好稳健，主要做长线"));
        assertEquals("稳健", profile.load("u1").get(UserProfileMemory.KEY_RISK));
        assertEquals("长线", profile.load("u1").get(UserProfileMemory.KEY_HORIZON));
    }

    @Test
    void agentMemoryHandlesConflictConfirmationFlow() {
        MemoryStore store = new MemoryStore(null);
        UserProfileMemory profile = new UserProfileMemory(store);
        AgentMemoryService service = new AgentMemoryService(new SessionMemory(store), profile,
                new ConclusionMemory(new InMemoryMemoryCardStore()), 2400, 30);

        service.handleUserSignals("u1", "我偏好稳健");
        String conflictNote = service.handleUserSignals("u1", "我偏好激进");
        assertNotNull(conflictNote);
        assertTrue(conflictNote.contains("记忆冲突"), "冲突应回传给模型去和用户确认");
        assertEquals("稳健", profile.load("u1").get(UserProfileMemory.KEY_RISK));

        String confirmNote = service.handleUserSignals("u1", "确认更新");
        assertNotNull(confirmNote);
        assertTrue(confirmNote.contains("已确认"));
        assertEquals("激进", profile.load("u1").get(UserProfileMemory.KEY_RISK));
    }

    @Test
    void agentMemoryBuildContextReportsWindowStats() {
        MemoryStore store = new MemoryStore(null);
        SessionMemory session = new SessionMemory(store);
        AgentMemoryService service = new AgentMemoryService(session, new UserProfileMemory(store),
                new ConclusionMemory(new InMemoryMemoryCardStore()), 2400, 30);

        session.record("s1", "user", "分析贵州茅台");
        session.record("s1", "tool", TOOL_ERROR_JSON);
        session.record("s1", "assistant", VALID_ANSWER);

        AgentMemoryService.MemoryContext context = service.buildContext("u1", "s1", "1.600519");

        assertEquals(2, context.chatMessages().size(), "工具报错不应进入对话窗口");
        assertEquals(1, context.filteredNoise());
        assertTrue(context.windowTokens() > 0);
        assertEquals(2, context.windowMessages());
    }

    @Test
    void conclusionAdmissionGatesLowQualityAnswers() {
        ConclusionMemory memory = new ConclusionMemory(new InMemoryMemoryCardStore());

        assertFalse(memory.evaluateAdmission("太短了", true, 3).admitted(), "过短回答不得沉淀");
        assertFalse(memory.evaluateAdmission("这是一段足够长的回答，用来验证缺少四维评分时会被准入规则拒绝，"
                + "因为历史结论必须是可以被复核的结构化输出而不是随口一说的闲聊内容。", true, 3).admitted(),
                "缺少四维评分不得沉淀");
        assertFalse(memory.evaluateAdmission(VALID_ANSWER, true, 0).admitted(),
                "没有任何工具观测支撑不得沉淀");
        assertTrue(memory.evaluateAdmission(VALID_ANSWER, true, 3).admitted());
    }

    @Test
    void conclusionSupersedeMarksOldAsStaleAndArchives() {
        ConclusionMemory memory = new ConclusionMemory(new InMemoryMemoryCardStore());

        ConclusionMemory.Conclusion first =
                memory.write("u1", "1.600519", "贵州茅台", "分析茅台", VALID_ANSWER, true, 3);
        assertNotNull(first);
        assertEquals("78", first.getScore());
        assertEquals("看多", first.getDirection());
        assertEquals("中", first.getConfidence());
        assertEquals(1, memory.loadUsable("u1", "1.600519").size());

        ConclusionMemory.Conclusion second =
                memory.write("u1", "1.600519", "贵州茅台", "再分析茅台", VALID_ANSWER, true, 3);
        assertNotNull(second);

        List<ConclusionMemory.Conclusion> usable = memory.loadUsable("u1", "1.600519");
        assertEquals(1, usable.size(), "同一标的只保留最新结论");
        assertEquals(second.getId(), usable.get(0).getId());

        List<ConclusionMemory.Conclusion> archive = memory.loadArchived("u1");
        assertEquals(1, archive.size());
        assertEquals(ConclusionMemory.Status.STALE, archive.get(0).getStatus());
        assertEquals("被同一标的最新的分析取代", archive.get(0).getInvalidReason());
    }

    @Test
    void conclusionIsNeverLeakedAcrossDifferentTargets() {
        ConclusionMemory memory = new ConclusionMemory(new InMemoryMemoryCardStore());
        memory.write("u1", "0.300750", "宁德时代", "分析宁德时代", VALID_ANSWER, true, 3);

        assertTrue(memory.render("u1", "1.600519").isEmpty(),
                "其他标的的历史结论不得注入，避免跨会话串题");
        assertFalse(memory.render("u1", "0.300750").isEmpty());
    }

    @Test
    void rejectedConclusionIsNotPersisted() {
        ConclusionMemory memory = new ConclusionMemory(new InMemoryMemoryCardStore());
        assertNull(memory.write("u1", "1.600519", "贵州茅台", "问一句", "闲聊内容", true, 0));
        assertEquals(0, memory.activeCount("u1"));
    }

    private static MemoryRecord record(MemoryRecord.Kind kind, String content, int importance,
                                       int tokens, boolean noise, long createdAt) {
        String role = switch (kind) {
            case USER_QUERY -> "user";
            case ASSISTANT_ANSWER -> "assistant";
            case TOOL_OBSERVATION -> "tool";
            case NOISE -> "tool";
        };
        return new MemoryRecord("r" + createdAt, "s1", role, content, kind, importance, tokens,
                noise, noise ? "测试构造的无效交互" : null, createdAt);
    }
}
