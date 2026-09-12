package com.example.javacodeagent.service;

import com.example.javacodeagent.rag.RagPaths;
import com.example.javacodeagent.rag.service.LocalVectorService;
import com.example.javacodeagent.rag.util.BM25Searcher;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 文档入库链路单测：用 mock 掉的 EmbeddingModel 替换 Ollama，其余走真实实现
 * （真实分块、真实 Lucene BM25、真实 JSON 持久化），因此不触网也能验证：
 * 上传 → 分块 → 向量 + BM25 双索引 → 清单与向量落盘。
 */
class DocumentServiceTest {

    private static final String DOC_TEXT = "贵州茅台发布2026年三季报。"
            + "报告期内公司营业收入同比增长15%。"
            + "归母净利润同比增长18%，毛利率维持在91%以上。"
            + "公司经营性现金流净额同比提升，渠道库存处于健康水平。";

    private DocumentService documentService;
    private LocalVectorService localVectorService;
    private BM25Searcher bm25Searcher;

    private byte[] manifestBackup;
    private boolean manifestExisted;
    private Set<Path> filesInDocsDirBefore;

    @BeforeEach
    void setUp() throws IOException {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(axisVector(0))));

        localVectorService = new LocalVectorService(new InMemoryEmbeddingStore<>());
        bm25Searcher = new BM25Searcher(RagPaths.DOCS_BM25_INDEX);
        documentService = new DocumentService(embeddingModel, localVectorService, bm25Searcher);

        manifestExisted = Files.exists(RagPaths.DOCS_MANIFEST);
        manifestBackup = manifestExisted ? Files.readAllBytes(RagPaths.DOCS_MANIFEST) : null;
        filesInDocsDirBefore = listFilesInDocsDir();
    }

    @AfterEach
    void cleanUp() throws IOException {
        // 只回收本测试新建的文件，避免动到使用者本地上传的研报
        for (Path file : listFilesInDocsDir()) {
            if (!filesInDocsDirBefore.contains(file)) {
                Files.deleteIfExists(file);
            }
        }
        if (manifestExisted) {
            Files.write(RagPaths.DOCS_MANIFEST, manifestBackup);
        } else {
            Files.deleteIfExists(RagPaths.DOCS_MANIFEST);
        }
    }

    @Test
    void uploadDocument_shouldWriteManifestAndIndexes() throws IOException {
        DocumentService.DocumentInfo info = documentService.uploadDocument(txtFile("茅台研报.txt"), "券商A");

        assertTrue(info.chunkCount() > 0, "应切出至少一个 chunk");
        assertEquals("券商A", info.source());

        List<DocumentService.DocumentInfo> docs = documentService.listDocuments();
        assertEquals(1, docs.size());
        assertEquals("茅台研报.txt", docs.get(0).originalName());

        assertFalse(bm25Searcher.searchTopChunks("贵州茅台").isEmpty(), "BM25 应能召回刚上传的文档");
        assertFalse(localVectorService.searchTopChunks(axisVector(0)).isEmpty(), "向量库应能召回刚上传的文档");
        assertTrue(Files.exists(RagPaths.DOCS_VECTOR_STORE), "向量存储应已持久化");
    }

    @Test
    void secondUpload_shouldNotDropFirstDocument() throws IOException {
        documentService.uploadDocument(txtFile("茅台研报.txt"), "券商A");
        documentService.uploadDocument(new MockMultipartFile("file", "宁德研报.txt", "text/plain",
                "宁德时代2026年三季报。动力电池装机量同比增长20%。海外订单占比继续提升。"
                        .getBytes(StandardCharsets.UTF_8)), "券商B");

        assertEquals(2, documentService.listDocuments().size(), "清单应保留两条记录");
        assertFalse(bm25Searcher.searchTopChunks("贵州茅台").isEmpty(), "先上传的文档不应被覆盖");
        assertFalse(bm25Searcher.searchTopChunks("宁德时代").isEmpty(), "后上传的文档应可检索");
    }

    @Test
    void unsupportedFormat_shouldBeRejected() {
        MockMultipartFile file = new MockMultipartFile("file", "研报.csv", "text/csv",
                "code,name".getBytes(StandardCharsets.UTF_8));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> documentService.uploadDocument(file, ""));

        assertTrue(ex.getMessage().contains("不支持的文件格式"));
        assertTrue(documentService.listDocuments().isEmpty());
    }

    @Test
    void blankDocument_shouldBeRejectedAndCleanUp() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "空白.txt", "text/plain",
                "   ".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> documentService.uploadDocument(file, ""));

        assertTrue(documentService.listDocuments().isEmpty());
        assertTrue(listFilesInDocsDir().stream().noneMatch(p -> p.getFileName().toString().endsWith("空白.txt")),
                "解析失败时应清掉已落盘的原始文件");
    }

    private MockMultipartFile txtFile(String name) {
        return new MockMultipartFile("file", name, "text/plain", DOC_TEXT.getBytes(StandardCharsets.UTF_8));
    }

    /** 768 维单位向量：仅 axis 维为 1。 */
    private static List<Float> axisVector(int axis) {
        List<Float> vector = new ArrayList<>(LocalVectorService.VECTOR_DIMENSION);
        for (int i = 0; i < LocalVectorService.VECTOR_DIMENSION; i++) {
            vector.add(i == axis ? 1.0f : 0.0f);
        }
        return vector;
    }

    private static Set<Path> listFilesInDocsDir() throws IOException {
        Path docsDir = Path.of(RagPaths.DOCS_DIR);
        if (!Files.exists(docsDir)) {
            return Set.of();
        }
        try (var paths = Files.walk(docsDir)) {
            return paths.filter(Files::isRegularFile).collect(Collectors.toCollection(HashSet::new));
        }
    }
}
