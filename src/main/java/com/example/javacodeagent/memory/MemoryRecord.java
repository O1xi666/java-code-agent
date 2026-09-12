package com.example.javacodeagent.memory;

import java.util.UUID;

/**
 * 会话记忆记录（三级记忆体系中"会话层"的存储单元）。
 *
 * <p>为什么不直接存 LangChain4j 的 {@code ChatMessage}：会话层需要做
 * 「重要性打分 + 动态 Token 窗口 + 无效交互过滤」，这些决策依赖每条消息的
 * 类型、重要性和 token 数等元数据，裸的 ChatMessage 无法承载。
 *
 * <p>刻意写成普通 Java Bean（而非 record）：项目里的 RedisTemplate 使用
 * {@code activateDefaultTyping(NON_FINAL)}，final 类不会被写入类型信息；
 * 本类统一以 JSON 字符串形式落库，普通 Bean 能保证序列化/反序列化稳定。
 */
public class MemoryRecord {

    /** 记录类型：动态 Token 窗口按此类型分配预算 */
    public enum Kind {
        /** 用户提问：上下文里必须保留的锚点 */
        USER_QUERY,
        /** 助手结论性回答 */
        ASSISTANT_ANSWER,
        /** 工具观测：可复用的关键数据 */
        TOOL_OBSERVATION,
        /** 无效交互：重试、报错、重复调用、中间态提示，默认不进上下文 */
        NOISE
    }

    private String id;
    private String sessionId;
    private String role;
    private String content;
    private Kind kind = Kind.USER_QUERY;
    /** 重要性打分 0~10，越高越优先占用 Token 预算 */
    private int importance;
    /** 该条内容的 token 数（JTokkit 口径） */
    private int tokens;
    private boolean noise;
    private String noiseReason;
    private long createdAt;

    public MemoryRecord() {
    }

    public MemoryRecord(String id, String sessionId, String role, String content, Kind kind,
                        int importance, int tokens, boolean noise, String noiseReason, long createdAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.role = role;
        this.content = content;
        this.kind = kind;
        this.importance = importance;
        this.tokens = tokens;
        this.noise = noise;
        this.noiseReason = noiseReason;
        this.createdAt = createdAt;
    }

    /** 工厂方法：自动生成 id 与时间戳 */
    public static MemoryRecord of(String sessionId, String role, String content, Kind kind,
                                  int importance, int tokens, boolean noise, String noiseReason) {
        return new MemoryRecord(UUID.randomUUID().toString().substring(0, 8), sessionId, role,
                content, kind, importance, tokens, noise, noiseReason, System.currentTimeMillis());
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }

    public int getImportance() { return importance; }
    public void setImportance(int importance) { this.importance = importance; }

    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }

    public boolean isNoise() { return noise; }
    public void setNoise(boolean noise) { this.noise = noise; }

    public String getNoiseReason() { return noiseReason; }
    public void setNoiseReason(String noiseReason) { this.noiseReason = noiseReason; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        return "MemoryRecord{role=" + role + ", kind=" + kind + ", importance=" + importance
                + ", tokens=" + tokens + ", noise=" + noise + "}";
    }
}
