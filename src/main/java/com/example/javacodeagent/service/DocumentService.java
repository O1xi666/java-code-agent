package com.example.javacodeagent.service;

import com.example.javacodeagent.rag.RagPaths;
import com.example.javacodeagent.rag.service.LocalVectorService;
import com.example.javacodeagent.rag.util.BM25Searcher;
import com.example.javacodeagent.rag.util.ChunkUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文档服务：负责文档上传、解析、分块、建索引
 *
 * 技术亮点（面试关注点）：
 * 1. 支持 PDF/DOCX/TXT 三种文档格式，覆盖常见研报类型
 * 2. 上传后自动分块（512 token，20% overlap）并建立向量 + BM25 双索引
 * 3. 向量存储持久化（toJson），启动时由 RagConfig 自动恢复
 * 4. 文档清单持久化到 rag-docs/manifest.json，支持列表查看
 *
 * 文档处理流程：
 * 上传 → 保存原始文件 → 解析文本 → 分块 → 向量化 → 存入向量库 → 存入 BM25 → 持久化
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    /** 上传文档根目录：与 RAG 检索侧共用同一份路径定义（{@link RagPaths}）。 */
    public static final String DOCS_DIR = RagPaths.DOCS_DIR;

    private static final Path MANIFEST_FILE = RagPaths.DOCS_MANIFEST;

    private final EmbeddingModel embeddingModel;
    private final LocalVectorService localVectorService;
    private final BM25Searcher bm25Searcher;
    private final ObjectMapper objectMapper;

    public DocumentService(
            EmbeddingModel embeddingModel,
            LocalVectorService localVectorService,
            BM25Searcher bm25Searcher
    ) {
        this.embeddingModel = embeddingModel;
        this.localVectorService = localVectorService;
        this.bm25Searcher = bm25Searcher;
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * 上传文档并建立索引
     *
     * @param file   上传的文件（PDF/DOCX/TXT）
     * @param source 可选来源名称
     * @return 文档信息
     */
    public DocumentInfo uploadDocument(MultipartFile file, String source) throws IOException {
        // 1. 校验文件类型
        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw new IllegalArgumentException("文件名不能为空");
        }
        String ext = getExtension(originalName).toLowerCase();
        if (!List.of("pdf", "docx", "txt").contains(ext)) {
            throw new IllegalArgumentException("不支持的文件格式: " + ext);
        }

        // 2. 保存原始文件
        Files.createDirectories(Path.of(DOCS_DIR));
        String docId = UUID.randomUUID().toString().substring(0, 8);
        String storedName = docId + "_" + originalName;
        Path storedPath = Path.of(DOCS_DIR, storedName);
        Files.copy(file.getInputStream(), storedPath, StandardCopyOption.REPLACE_EXISTING);

        // 3. 解析文本
        String text = ChunkUtils.readSupportedDocument(storedPath);
        String cleanedText = ChunkUtils.cleanText(text);

        // 4. 分块（沿用 ChunkUtils 默认的 512 token / 20% overlap）
        List<ChunkUtils.Chunk> chunks = ChunkUtils.chunkByToken(cleanedText, originalName);
        if (chunks.isEmpty()) {
            Files.deleteIfExists(storedPath);
            throw new IllegalArgumentException("文档未解析出有效文本（可能是扫描版 PDF 或空文件）: " + originalName);
        }

        // 5. 向量化并存入向量库 + BM25
        List<LocalVectorService.VectorRecord> vectorRecords = new ArrayList<>();
        List<BM25Searcher.Bm25Chunk> bm25Chunks = new ArrayList<>();

        for (ChunkUtils.Chunk chunk : chunks) {
            // 生成向量
            Response<Embedding> embeddingResponse = embeddingModel.embed(chunk.content());
            Embedding embedding = embeddingResponse.content();
            List<Float> vector = embedding.vectorAsList();

            vectorRecords.add(new LocalVectorService.VectorRecord(
                    chunk.id(), chunk.content(), chunk.source(), chunk.tokenCount(), vector
            ));

            bm25Chunks.add(new BM25Searcher.Bm25Chunk(chunk.id(), chunk.content()));
        }

        // 批量插入向量库
        localVectorService.insertBatch(vectorRecords);

        // 增量追加 BM25 索引：多文档共存，后上传的文档不会覆盖已有文档
        bm25Searcher.appendChunks(bm25Chunks);

        log.info("文档索引完成: {} → {} 个 chunk", originalName, chunks.size());

        // 6. 保存文档清单
        String normalizedSource = source == null ? "" : source;
        DocumentInfo info = new DocumentInfo(
                docId, originalName, file.getSize(), ext,
                Instant.now(), chunks.size(), storedPath.toString(), normalizedSource
        );
        saveDocumentInfo(info);

        // 7. 持久化向量存储，重启后由 RagConfig 自动恢复
        localVectorService.persistStore();

        return info;
    }

    /**
     * 获取所有已上传的文档列表
     */
    public List<DocumentInfo> listDocuments() {
        if (!Files.exists(MANIFEST_FILE)) {
            return List.of();
        }
        try {
            String json = Files.readString(MANIFEST_FILE, StandardCharsets.UTF_8);
            List<DocumentInfo> loaded = objectMapper.readValue(json, new TypeReference<List<DocumentInfo>>() {});
            return loaded == null ? List.of() : loaded;
        } catch (Exception e) {
            log.warn("读取文档清单失败: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 把文档信息追加写入 manifest.json（读-改-写，条目量级很小，全量重写即可）。
     */
    private void saveDocumentInfo(DocumentInfo info) {
        List<DocumentInfo> all = new ArrayList<>(listDocuments());
        all.add(info);
        try {
            Files.createDirectories(MANIFEST_FILE.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(MANIFEST_FILE.toFile(), all);
            log.info("文档清单已更新: {} 条", all.size());
        } catch (IOException e) {
            throw new IllegalStateException("文档清单写入失败: " + MANIFEST_FILE, e);
        }
    }

    private String getExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot == -1 ? "" : filename.substring(dot + 1);
    }

    /**
     * 文档信息 VO
     */
    public record DocumentInfo(
            String id,
            String originalName,
            long fileSize,
            String contentType,
            Instant uploadTime,
            int chunkCount,
            String storedPath,
            String source
    ) {}
}
