# -*- coding: utf-8 -*-
"""Rewrite ChunkUtils.chunkByToken() with configurable token size, overlap, and sentence-boundary awareness."""
import re

# ── ChunkUtils.java ──
path = r'E:\github\JavaAgent\java-code-agent\src\main\java\com\example\javacodeagent\rag\util\ChunkUtils.java'
with open(path, 'r', encoding='utf-8') as f:
    content = f.read()

# 1. Remove old constants (CHUNK_SIZE_TOKENS, OVERLAP_RATIO, OVERLAP_TOKENS, STRIDE_TOKENS)
#    Replace with new method signature
old_constants = '''    /**
     * 每个分块的目标 token 数。
     */
    private static final int CHUNK_SIZE_TOKENS = 512;

    /**
     * 分块重叠比例，20%。
     */
    private static final double OVERLAP_RATIO = 0.20d;

    /**
     * 20% overlap 对应的 token 数（512 * 0.2 = 102.4，向下取整为 102）。
     */
    private static final int OVERLAP_TOKENS = (int) (CHUNK_SIZE_TOKENS * OVERLAP_RATIO);

    /**
     * 分块步长：每次向前推进的 token 数。
     */
    private static final int STRIDE_TOKENS = CHUNK_SIZE_TOKENS - OVERLAP_TOKENS;'''

new_constants = '''    /**
     * 默认分块 token 数（仅在无参重载时使用）。
     */
    private static final int DEFAULT_CHUNK_SIZE_TOKENS = 400;

    /**
     * 默认重叠比例。
     */
    private static final double DEFAULT_OVERLAP_RATIO = 0.20d;'''

content = content.replace(old_constants, new_constants)

# 2. Replace chunkByToken method with new sentence-boundary aware version
old_chunk = '''    /**
     * 核心分块逻辑：
     * - 固定窗口：512 token
     * - 固定重叠：102 token（20%）
     */
    public static List<Chunk> chunkByToken(String cleanedText, String source) {
        List<Chunk> chunks = new ArrayList<>();
        if (cleanedText == null || cleanedText.isBlank()) {
            return chunks;
        }

        IntArrayList allTokens = ENCODING.encode(cleanedText);
        if (allTokens.isEmpty()) {
            return chunks;
        }

        int cursor = 0;
        int index = 1;
        while (cursor < allTokens.size()) {
            int end = Math.min(cursor + CHUNK_SIZE_TOKENS, allTokens.size());
            IntArrayList tokenWindow = new IntArrayList(end - cursor);
            for (int i = cursor; i < end; i++) {
                tokenWindow.add(allTokens.get(i));
            }
            String chunkText = ENCODING.decode(tokenWindow).trim();

            if (!chunkText.isBlank()) {
                chunks.add(new Chunk(
                        generateChunkId(source, index),
                        chunkText,
                        source,
                        tokenWindow.size()
                ));
                index++;
            }

            if (end >= allTokens.size()) {
                break;
            }

            // 固定步长推进，保证 20% overlap
            cursor += STRIDE_TOKENS;
        }

        return chunks;
    }'''

new_chunk = '''    /**
     * 句子边界感知分块：
     * 1. 先按句子分割（。；！？）
     * 2. 累加句子至达到 chunkSize（token）
     * 3. 按重叠比例从上一块尾部回退若干 token 开始下一块
     * 4. 绝不腰斩句子
     */
    public static List<Chunk> chunkByToken(String cleanedText, String source) {
        return chunkByToken(cleanedText, source, DEFAULT_CHUNK_SIZE_TOKENS, DEFAULT_OVERLAP_RATIO);
    }

    /**
     * 句子边界感知分块（可配置参数）。
     *
     * @param cleanedText    清洗后的文本
     * @param source         来源标识
     * @param chunkSizeTokens 每块目标 token 数
     * @param overlapRatio    重叠比例（0.0 ~ 1.0）
     */
    public static List<Chunk> chunkByToken(String cleanedText, String source, int chunkSizeTokens, double overlapRatio) {
        List<Chunk> chunks = new ArrayList<>();
        if (cleanedText == null || cleanedText.isBlank()) return chunks;

        // 1. 按句子分割
        //    保留分隔符在前一句末尾，如 "。；！？.!?" 
        String[] rawParts = cleanedText.split("(?<=[\\u3002\\uff1b\\uff01\\uff1f.!?])");
        List<String> sentences = new ArrayList<>();
        for (String s : rawParts) {
            String t = s.trim();
            if (!t.isBlank()) sentences.add(t);
        }
        if (sentences.isEmpty()) return chunks;

        // 2. 预计算每句的 token 数
        List<IntArrayList> sentenceTokens = new ArrayList<>(sentences.size());
        for (String s : sentences) {
            sentenceTokens.add(ENCODING.encode(s));
        }

        int overlapTokens = (int) Math.round(chunkSizeTokens * overlapRatio);
        if (overlapTokens < 0) overlapTokens = 0;
        int minStride = Math.max(1, chunkSizeTokens - overlapTokens);

        int index = 1;
        int cursor = 0;
        while (cursor < sentences.size()) {
            // 累加句子直到达到 chunkSize 或末尾
            int tokenAccum = 0;
            int endIdx = cursor;
            while (endIdx < sentences.size() && tokenAccum < chunkSizeTokens) {
                tokenAccum += sentenceTokens.get(endIdx).size();
                endIdx++;
            }
            if (endIdx == cursor) break; // 保护：单个句子超 chunkSize 时也要推进

            // 构建当前块文本
            String chunkText = String.join("", sentences.subList(cursor, endIdx)).trim();
            if (!chunkText.isBlank()) {
                chunks.add(new Chunk(generateChunkId(source, index), chunkText, source, tokenAccum));
                index++;
            }

            if (endIdx >= sentences.size()) break;

            // 计算下一块的起始位置：从 endIdx 向前回退 overlapTokens
            int newCursor = endIdx;
            int backAccum = 0;
            while (newCursor > cursor && backAccum < overlapTokens) {
                newCursor--;
                backAccum += sentenceTokens.get(newCursor).size();
            }
            // 保证至少向前推进 1 句
            if (newCursor <= cursor) newCursor = cursor + 1;
            cursor = newCursor;
        }

        return chunks;
    }'''

content = content.replace(old_chunk, new_chunk)

# 3. Update buildChunks javadoc and pass parameters
old_build = '''    /**
     * 读取并处理文档，最终输出可入向量库的 Chunk 列表。
     *
     * @param filePath 文档路径，仅支持 .txt/.docx
     * @return Chunk 列表（已含 id、来源、token 数）
     * @throws IOException 文件读取失败时抛出
     */
    public static List<Chunk> buildChunks(Path filePath) throws IOException {
        String rawText = readSupportedDocument(filePath);
        String cleanedText = cleanText(rawText);
        return chunkByToken(cleanedText, filePath.toString());
    }'''

new_build = '''    /**
     * 读取并处理文档（使用默认参数：400 token, 20% overlap），最终输出可入向量库的 Chunk 列表。
     *
     * @param filePath 文档路径，仅支持 .txt/.docx
     * @return Chunk 列表（已含 id、来源、token 数）
     * @throws IOException 文件读取失败时抛出
     */
    public static List<Chunk> buildChunks(Path filePath) throws IOException {
        return buildChunks(filePath, DEFAULT_CHUNK_SIZE_TOKENS, DEFAULT_OVERLAP_RATIO);
    }

    /**
     * 读取并处理文档（可配置分块参数），最终输出可入向量库的 Chunk 列表。
     *
     * @param filePath         文档路径，仅支持 .txt/.docx
     * @param chunkSizeTokens  每块目标 token 数
     * @param overlapRatio     重叠比例
     * @return Chunk 列表（已含 id、来源、token 数）
     * @throws IOException 文件读取失败时抛出
     */
    public static List<Chunk> buildChunks(Path filePath, int chunkSizeTokens, double overlapRatio) throws IOException {
        String rawText = readSupportedDocument(filePath);
        String cleanedText = cleanText(rawText);
        return chunkByToken(cleanedText, filePath.toString(), chunkSizeTokens, overlapRatio);
    }'''

content = content.replace(old_build, new_build)

with open(path, 'w', encoding='utf-8') as f:
    f.write(content)
print('ChunkUtils.java updated')

# ── KnowledgeBaseService.java ──
path2 = r'E:\github\JavaAgent\java-code-agent\src\main\java\com\example\javacodeagent\rag\service\KnowledgeBaseService.java'
with open(path2, 'r', encoding='utf-8') as f:
    content2 = f.read()

# Add ChunkUtils import
if 'import com.example.javacodeagent.rag.util.ChunkUtils;' not in content2:
    content2 = content2.replace(
        'import com.example.javacodeagent.rag.util.BM25Searcher;',
        'import com.example.javacodeagent.rag.util.BM25Searcher;\nimport com.example.javacodeagent.rag.util.ChunkUtils;'
    )

# Replace splitContentIfLong usage in importCsv
old_import_loop = '''                    for (String chunk : splitContentIfLong(content)) {
                        parsed.add(KnowledgeEntry.fromRow(stockCode, stockName, chunk, category, source, tags));
                    }'''

new_import_loop = '''                    for (ChunkUtils.Chunk chunk : ChunkUtils.chunkByToken(content, "csv:" + stockCode + ":" + stockName, 300, 0.10)) {
                        parsed.add(KnowledgeEntry.fromRow(stockCode, stockName, chunk.content(), category, source, tags));
                    }'''

content2 = content2.replace(old_import_loop, new_import_loop)

# Remove splitContentIfLong method (find and delete the whole method)
old_split = '''    private List<String> splitContentIfLong(String content) {
        if (content.length() <= 800) return List.of(content);
        List<String> chunks = new ArrayList<>();
        String[] parts = content.split("(?<=[\\u3002\\uff1b.!?])\\s*");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (current.length() + part.length() > 800 && current.length() > 0) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(part);
        }
        if (current.length() > 0) chunks.add(current.toString().trim());
        return chunks.isEmpty() ? List.of(content) : chunks;
    }'''

content2 = content2.replace(old_split, '')

with open(path2, 'w', encoding='utf-8') as f:
    f.write(content2)
print('KnowledgeBaseService.java updated')

# ── DocumentService.java ──
path3 = r'E:\github\JavaAgent\java-code-agent\src\main\java\com\example\javacodeagent\service\DocumentService.java'
with open(path3, 'r', encoding='utf-8') as f:
    content3 = f.read()

content3 = content3.replace(
    'List<ChunkUtils.Chunk> chunks = ChunkUtils.chunkByToken(cleanedText, originalName);',
    'List<ChunkUtils.Chunk> chunks = ChunkUtils.chunkByToken(cleanedText, originalName, 400, 0.20);'
)

with open(path3, 'w', encoding='utf-8') as f:
    f.write(content3)
print('DocumentService.java updated')

print('Done!')
