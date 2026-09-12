package com.example.javacodeagent.service;

import com.example.javacodeagent.memory.DynamicTokenWindow;
import com.example.javacodeagent.memory.ImportanceScorer;
import com.example.javacodeagent.memory.MemoryJsonUtil;
import com.example.javacodeagent.memory.MemoryRecord;
import com.example.javacodeagent.memory.MemoryStore;
import com.example.javacodeagent.memory.MemoryTokenizer;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 短期记忆（三级记忆体系的「会话层」）。
 *
 * <p>职责边界：本类只负责"存"和"打分"，不负责决定"哪些进上下文"——
 * 窗口裁剪交给 {@link DynamicTokenWindow}，编排交给 {@link AgentMemoryService}，
 * 这样每一层都能被单独测试。
 *
 * <p>相比早期"存储全量对话、推理时固定取最近 N 条"的实现，这里做了两件事：
 * <ol>
 *   <li>写入时给每条消息打重要性分并标记噪声（工具报错/重试/重复调用/中间态），
 *       噪声照常留档以便排查，但不会进入推理上下文；</li>
 *   <li>落库时记录 token 数，让动态 Token 窗口可以按真实预算裁剪而不是按条数。</li>
 * </ol>
 *
 * <p>会话历史保留 7 天，匹配用户"过几天回来继续同一会话"的使用习惯。
 */
@Component
public class SessionMemory {

    private static final Logger log = LoggerFactory.getLogger(SessionMemory.class);

    private static final String KEY_PREFIX = "session:memory:";
    private static final Duration TTL = Duration.ofDays(7);
    /** 单会话最多留档的记录数，防止 Redis 无限增长 */
    private static final int MAX_RECORDS = 200;

    private final MemoryStore store;

    public SessionMemory(MemoryStore store) {
        this.store = store;
    }

    /** 读取会话全部留档记录（含被标记的噪声） */
    public List<MemoryRecord> load(String sessionId) {
        List<MemoryRecord> records = MemoryJsonUtil.read(store.get(key(sessionId)),
                new TypeReference<ArrayList<MemoryRecord>>() {
                });
        return records == null ? new ArrayList<>() : records;
    }

    /**
     * 记录一条消息：先打分、再计 token、最后落库。
     *
     * @return 生成的记忆记录（含重要性、token 数、噪声标记），便于调用方打日志
     */
    public MemoryRecord record(String sessionId, String role, String content) {
        ImportanceScorer.Scored scored = ImportanceScorer.score(role, content);
        MemoryRecord record = MemoryRecord.of(sessionId, role, content, scored.kind(),
                scored.importance(), MemoryTokenizer.count(content), scored.noise(), scored.reason());

        List<MemoryRecord> records = load(sessionId);
        records.add(record);
        while (records.size() > MAX_RECORDS) {
            records.remove(0);
        }
        store.put(key(sessionId), MemoryJsonUtil.write(records), TTL);
        return record;
    }

    public void saveUserMessage(String sessionId, String content) {
        record(sessionId, "user", content);
    }

    public void saveAssistantMessage(String sessionId, String content) {
        record(sessionId, "assistant", content);
    }

    /** 会话是否已有历史（用于决定能否命中 LLM 结果缓存） */
    public boolean hasSession(String sessionId) {
        return !load(sessionId).isEmpty();
    }

    public void clear(String sessionId) {
        store.remove(key(sessionId));
        log.info("会话记忆已清空: session={}", sessionId);
    }

    private static String key(String sessionId) {
        return KEY_PREFIX + (sessionId == null || sessionId.isBlank() ? "default-session" : sessionId);
    }
}
