package com.example.javacodeagent.rag.util;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithRange;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.ModelType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 上下文管理器：
 * <ul>
 *     <li>基于 Redis 存储多轮对话上下文</li>
 *     <li>基于 JavaParser 按“方法粒度”切分 Java 代码</li>
 *     <li>每个代码 chunk 不超过 512 token（超长方法走滑动窗口）</li>
 * </ul>
 */
@Component
public class ContextManager {

    /**
     * 单条对话过期时间（小时）。
     */
    private static final long CONTEXT_TTL_HOURS = 24L;

    /**
     * 最多分析代码行数。
     */
    private static final int MAX_ANALYZE_LINES = 1000;

    /**
     * 每个代码 chunk 最大 token。
     */
    private static final int MAX_CHUNK_TOKENS = 512;

    /**
     * 滑动窗口重叠比例（20%）。
     */
    private static final double OVERLAP_RATIO = 0.20d;

    private static final int OVERLAP_TOKENS = (int) (MAX_CHUNK_TOKENS * OVERLAP_RATIO);
    private static final int STRIDE = MAX_CHUNK_TOKENS - OVERLAP_TOKENS;

    private static final String KEY_PREFIX = "rag:context:";
    private static final String SEPARATOR = "\n---\n";

    private final StringRedisTemplate redisTemplate;
    private final JavaParser javaParser;
    private final Encoding encoding;

    public ContextManager(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.javaParser = new JavaParser(new ParserConfiguration());

        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);
    }

    /**
     * 追加单轮对话到 Redis。
     *
     * @param conversationId 会话 ID（同一会话多轮复用）
     * @param role           角色：user/assistant/system
     * @param content        内容
     */
    public void appendConversationTurn(String conversationId, String role, String content) {
        String safeConversationId = normalizeConversationId(conversationId);
        String safeRole = normalizeRole(role);
        String safeContent = Objects.toString(content, "").trim();

        String redisKey = buildContextKey(safeConversationId);
        String turn = "[%s][%s]%s".formatted(Instant.now(), safeRole, safeContent);

        redisTemplate.opsForValue().append(redisKey, turn + SEPARATOR);
        redisTemplate.expire(redisKey, Duration.ofHours(CONTEXT_TTL_HOURS));
    }

    /**
     * 获取会话完整上下文文本。
     */
    public String getConversationContext(String conversationId) {
        String redisKey = buildContextKey(normalizeConversationId(conversationId));
        String raw = redisTemplate.opsForValue().get(redisKey);
        return raw == null ? "" : raw;
    }

    /**
     * 删除会话上下文。
     */
    public void clearConversationContext(String conversationId) {
        redisTemplate.delete(buildContextKey(normalizeConversationId(conversationId)));
    }

    /**
     * 获取最近 N 轮上下文（按写入顺序截取尾部）。
     */
    public List<String> getRecentTurns(String conversationId, int turns) {
        if (turns <= 0) {
            return List.of();
        }

        String context = getConversationContext(conversationId);
        if (context.isBlank()) {
            return List.of();
        }

        String[] parts = context.split(SEPARATOR);
        List<String> all = new ArrayList<>();
        for (String p : parts) {
            if (!p.isBlank()) {
                all.add(p.trim());
            }
        }
        if (all.isEmpty()) {
            return List.of();
        }

        int from = Math.max(0, all.size() - turns);
        return all.subList(from, all.size());
    }

    /**
     * 按“方法粒度”拆分 Java 代码，并保证每个 chunk <= 512 token。
     * <p>
     * 处理流程：
     * <ol>
     *     <li>先将输入限制在最多 1000 行</li>
     *     <li>使用 JavaParser 抽取方法/构造器</li>
     *     <li>每个方法若 <=512 token，直接作为一个 chunk</li>
     *     <li>若 >512 token，则对方法体文本做滑动窗口拆分</li>
     * </ol>
     */
    public List<CodeChunk> splitJavaCodeByMethod(String javaCode, String sourceTag) {
        String normalized = normalizeCodeWithLineLimit(javaCode, MAX_ANALYZE_LINES);
        if (normalized.isBlank()) {
            return List.of();
        }

        ParseResult<CompilationUnit> parseResult = javaParser.parse(normalized);
        if (parseResult.getResult().isEmpty()) {
            return fallbackChunkBySlidingWindow(normalized, sourceTag, "parse_fallback");
        }

        CompilationUnit cu = parseResult.getResult().get();
        List<CodeChunk> chunks = new ArrayList<>();

        int index = 1;
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            chunks.addAll(splitMemberToChunks(
                    method,
                    method.getNameAsString(),
                    sourceTag,
                    index++
            ));
        }

        for (ConstructorDeclaration constructor : cu.findAll(ConstructorDeclaration.class)) {
            chunks.addAll(splitMemberToChunks(
                    constructor,
                    constructor.getNameAsString(),
                    sourceTag,
                    index++
            ));
        }

        if (chunks.isEmpty()) {
            return fallbackChunkBySlidingWindow(normalized, sourceTag, "no_method_fallback");
        }

        return chunks;
    }

    private List<CodeChunk> splitMemberToChunks(
            NodeWithRange<?> member,
            String memberName,
            String sourceTag,
            int serial
    ) {
        String code = member.toString();
        IntArrayList tokens = encoding.encode(code);

        if (tokens.size() <= MAX_CHUNK_TOKENS) {
            return List.of(new CodeChunk(
                    chunkId(sourceTag, memberName, serial, 1),
                    code,
                    sourceTag,
                    memberName,
                    tokens.size()
            ));
        }

        List<CodeChunk> chunks = new ArrayList<>();
        int window = 1;
        for (int start = 0; start < tokens.size(); start += STRIDE) {
            int end = Math.min(start + MAX_CHUNK_TOKENS, tokens.size());
            IntArrayList tokenSlice = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                tokenSlice.add(tokens.get(i));
            }

            String sliceText = encoding.decode(tokenSlice).trim();
            if (!sliceText.isBlank()) {
                chunks.add(new CodeChunk(
                        chunkId(sourceTag, memberName, serial, window++),
                        sliceText,
                        sourceTag,
                        memberName,
                        tokenSlice.size()
                ));
            }

            if (end >= tokens.size()) {
                break;
            }
        }
        return chunks;
    }

    private List<CodeChunk> fallbackChunkBySlidingWindow(String code, String sourceTag, String memberName) {
        IntArrayList tokens = encoding.encode(code);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<CodeChunk> chunks = new ArrayList<>();
        int window = 1;
        for (int start = 0; start < tokens.size(); start += STRIDE) {
            int end = Math.min(start + MAX_CHUNK_TOKENS, tokens.size());
            IntArrayList tokenSlice = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                tokenSlice.add(tokens.get(i));
            }

            chunks.add(new CodeChunk(
                    chunkId(sourceTag, memberName, 0, window++),
                    encoding.decode(tokenSlice).trim(),
                    sourceTag,
                    memberName,
                    tokenSlice.size()
            ));

            if (end >= tokens.size()) {
                break;
            }
        }
        return chunks;
    }

    /**
     * 规范化代码并限制最大行数，确保最多分析 1000 行。
     */
    private String normalizeCodeWithLineLimit(String code, int maxLines) {
        if (code == null || code.isBlank()) {
            return "";
        }

        String[] lines = code.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int limit = Math.min(lines.length, maxLines);

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            builder.append(lines[i]).append('\n');
        }
        return builder.toString().trim();
    }

    private String buildContextKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    private String normalizeConversationId(String conversationId) {
        String safe = Objects.toString(conversationId, "").trim();
        if (safe.isBlank()) {
            throw new IllegalArgumentException("conversationId must not be blank");
        }
        return safe;
    }

    private String normalizeRole(String role) {
        String safe = Objects.toString(role, "user").trim().toLowerCase(Locale.ROOT);
        return switch (safe) {
            case "user", "assistant", "system" -> safe;
            default -> "user";
        };
    }

    private String chunkId(String sourceTag, String memberName, int serial, int window) {
        return "code-" + sourceTag.hashCode() + "-" + memberName.hashCode() + "-" + serial + "-" + window;
    }

    /**
     * 代码 chunk 实体。
     *
     * @param id         chunk ID
     * @param content    chunk 内容
     * @param source     来源标识（文件路径/类名等）
     * @param memberName 方法名（或 fallback 名）
     * @param tokenCount token 数
     */
    public record CodeChunk(
            String id,
            String content,
            String source,
            String memberName,
            int tokenCount
    ) {
    }
}
