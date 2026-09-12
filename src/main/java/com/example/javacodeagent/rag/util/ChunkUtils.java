package com.example.javacodeagent.rag.util;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.IntArrayList;
import com.knuddels.jtokkit.api.ModelType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * RAG 文档处理工具：
 * <ul>
 *     <li>读取 txt/docx/pdf 规范文档</li>
 *     <li>执行文本清洗</li>
 *     <li>使用 JTokkit 按 token 进行固定窗口分块（512 token，20% overlap）</li>
 * </ul>
 *
 * <p>说明：为避免影响现有代码，这里采用纯工具类实现，调用方可直接静态方法使用。</p>
 */
public final class ChunkUtils {

    /**
     * 每个分块的目标 token 数。
     */
    private static final int CHUNK_SIZE_TOKENS = 512;

    /**
     * 分块重叠比例：20%。
     */
    private static final double OVERLAP_RATIO = 0.20d;

    /**
     * 20% overlap 对应的 token 数（512 * 0.2 = 102.4，向下取整为 102）。
     */
    private static final int OVERLAP_TOKENS = (int) (CHUNK_SIZE_TOKENS * OVERLAP_RATIO);

    /**
     * 分块步长：每次向前推进的 token 数。
     */
    private static final int STRIDE_TOKENS = CHUNK_SIZE_TOKENS - OVERLAP_TOKENS;
    private static final int DEFAULT_CHUNK_SIZE_TOKENS = 400;
    private static final double DEFAULT_OVERLAP_RATIO = 0.20d;

    /**
     * 控制字符（除换行、回车、制表符）清理正则。
     */
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");

    /**
     * 每行内多余空白压缩正则。
     */
    private static final Pattern MULTI_SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0\\u2000-\\u200A\\u202F\\u205F\\u3000]+");

    /**
     * 多空行压缩为单空行正则。
     */
    private static final Pattern MULTI_BLANK_LINES = Pattern.compile("(\\R\\s*){2,}");

    /**
     * XML 标签清理正则（用于 docx 的 document.xml）。
     */
    private static final Pattern XML_TAGS = Pattern.compile("<[^>]+>");

    /**
     * 语义分块使用 gpt-3.5-turbo 编码。
     */
    private static final Encoding ENCODING;

    static {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        ENCODING = registry.getEncodingForModel(ModelType.GPT_3_5_TURBO);
    }

    private ChunkUtils() {
        // 工具类无需实例化
    }

    /**
     * 读取并处理文档，最终输出可直接入向量库的 Chunk 列表。
     *
     * @param filePath 文档路径，仅支持 .txt/.docx/.pdf
     * @return Chunk 列表（已含 id、来源、token 数）
     * @throws IOException 文件读取失败时抛出
     */
    public static List<Chunk> buildChunks(Path filePath) throws IOException {
        String rawText = readSupportedDocument(filePath);
        String cleanedText = cleanText(rawText);
        return chunkByToken(cleanedText, filePath.toString());
    }

    /**
     * 支持 txt/docx/pdf 文档读取。
     */
    public static String readSupportedDocument(Path filePath) throws IOException {
        String filename = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (filename.endsWith(".txt")) {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        }
        if (filename.endsWith(".docx")) {
            return readDocxText(filePath);
        }
        if (filename.endsWith(".pdf")) {
            return readPdfText(filePath);
        }
        throw new IllegalArgumentException("Only .txt/.docx/.pdf are supported: " + filePath);
    }

    /**
     * 文本清洗：
     * 1) Unicode 归一化
     * 2) 去除不可见控制字符
     * 3) 压缩冗余空白
     * 4) 删除多余空行
     * 5) 去首尾空白
     */
    public static String cleanText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String normalized = Normalizer.normalize(rawText, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');

        // 去除不可见控制字符，保留换行和制表符，便于段落结构保留。
        normalized = CONTROL_CHARS.matcher(normalized).replaceAll("");

        // 按行清理，避免把段落边界完全抹掉。
        StringBuilder lineBuilder = new StringBuilder(normalized.length());
        for (String line : normalized.split("\n", -1)) {
            String compactedLine = MULTI_SPACES.matcher(line).replaceAll(" ").trim();
            lineBuilder.append(compactedLine).append('\n');
        }

        String compacted = lineBuilder.toString();
        compacted = MULTI_BLANK_LINES.matcher(compacted).replaceAll("\n\n");
        return compacted.trim();
    }

    /**
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
        String[] rawParts = cleanedText.split("(?<=[\u3002\uff1b\uff01\uff1f.!?])");
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
    }

    /**
     * 简单可追踪的 Chunk ID：基于 source + 顺序 + UUID 前缀。
     */
    private static String generateChunkId(String source, int index) {
        String sourceHash = Integer.toHexString(source.hashCode());
        String uuidShort = UUID.randomUUID().toString().substring(0, 8);
        return "chunk-" + sourceHash + "-" + index + "-" + uuidShort;
    }

    /**
     * 读取 docx 文本：
     * <p>docx 本质是 zip，正文在 word/document.xml。</p>
     * <p>这里不改动你现有依赖结构，直接解析 XML 文本并进行实体反转义。</p>
     */
    private static String readDocxText(Path filePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(filePath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.getEntry("word/document.xml");
            if (entry == null) {
                throw new IllegalArgumentException("Invalid docx, missing word/document.xml: " + filePath);
            }

            String xml = new String(zipFile.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);

            // 段落/换行节点先转为显式换行，避免后续去标签后粘连。
            xml = xml.replaceAll("</w:p>", "\n")
                    .replaceAll("<w:br\\s*/>", "\n")
                    .replaceAll("<w:tab\\s*/>", "\t");

            String textWithoutTags = XML_TAGS.matcher(xml).replaceAll("");
            return Parser.unescapeEntities(textWithoutTags, false);
        }
    }

    /**
     * 读取 pdf 文本（字节流可能压缩，交由 PDFBox 解析）。
     * <p>开启按位置排序，尽量还原双栏研报的阅读顺序。</p>
     */
    private static String readPdfText(Path filePath) throws IOException {
        try (PDDocument document = Loader.loadPDF(filePath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    /**
     * Chunk 实体：用于后续向量入库/检索阶段传递。
     *
     * @param id         chunk 唯一标识
     * @param content    原文片段
     * @param source     文档来源（通常是路径或逻辑来源名）
     * @param tokenCount 该 chunk 的 token 数
     */
    public record Chunk(
            String id,
            String content,
            String source,
            int tokenCount
    ) {
    }
}
