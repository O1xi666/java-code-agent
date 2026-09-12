package com.example.javacodeagent.rag.service;

import com.example.javacodeagent.rag.RagPaths;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 本地向量服务单测（不触网、不需要 Ollama：直接构造向量）。
 *
 * 覆盖检索命中顺序与"持久化 → 重启恢复"链路：
 * 早期实现只有注释里的 persistStore() 计划，重启后上传的研报向量会全部丢失。
 */
class LocalVectorServiceTest {

    private boolean storeExistedBefore;
    private byte[] storeBackup;

    @AfterEach
    void restoreStoreFile() throws IOException {
        Path store = RagPaths.DOCS_VECTOR_STORE;
        if (storeExistedBefore) {
            Files.write(store, storeBackup);
        } else {
            Files.deleteIfExists(store);
        }
    }

    @Test
    void searchTopChunks_shouldRankNearestVectorFirst() throws IOException {
        snapshotStoreFile();
        LocalVectorService service = new LocalVectorService(new InMemoryEmbeddingStore<>());
        service.insertBatch(List.of(
                new LocalVectorService.VectorRecord("maotai", "贵州茅台三季报", "研报A", 10, axisVector(0)),
                new LocalVectorService.VectorRecord("catl", "宁德时代装机量", "研报B", 10, axisVector(1))
        ));

        List<LocalVectorService.SimilarChunk> hits = service.searchTopChunks(axisVector(0));

        assertFalse(hits.isEmpty());
        assertEquals("maotai", hits.get(0).chunkId());
        assertEquals("研报A", hits.get(0).source());
    }

    @Test
    void persistStore_shouldBeReloadableAfterRestart() throws IOException {
        snapshotStoreFile();
        LocalVectorService service = new LocalVectorService(new InMemoryEmbeddingStore<>());
        service.insertBatch(List.of(
                new LocalVectorService.VectorRecord("maotai", "贵州茅台三季报", "研报A", 10, axisVector(0))
        ));

        service.persistStore();
        assertTrue(Files.exists(RagPaths.DOCS_VECTOR_STORE), "持久化文件应落盘");

        // 模拟重启：从磁盘重新加载一个全新的存储
        LocalVectorService restarted = new LocalVectorService(LocalVectorService.loadPersistedStore());
        List<LocalVectorService.SimilarChunk> hits = restarted.searchTopChunks(axisVector(0));

        assertFalse(hits.isEmpty(), "重启后应仍能召回已上传文档");
        assertEquals("maotai", hits.get(0).chunkId());
    }

    @Test
    void wrongDimensionQuery_shouldReturnEmpty() {
        LocalVectorService service = new LocalVectorService(new InMemoryEmbeddingStore<>());

        assertTrue(service.searchTopChunks(List.of(1.0f, 0.0f)).isEmpty());
        assertTrue(service.searchTopChunks(null).isEmpty());
    }

    private void snapshotStoreFile() throws IOException {
        Path store = RagPaths.DOCS_VECTOR_STORE;
        storeExistedBefore = Files.exists(store);
        storeBackup = storeExistedBefore ? Files.readAllBytes(store) : null;
    }

    /** 构造 768 维单位向量：仅 axis 维为 1，其余为 0。 */
    private static List<Float> axisVector(int axis) {
        List<Float> vector = new ArrayList<>(LocalVectorService.VECTOR_DIMENSION);
        for (int i = 0; i < LocalVectorService.VECTOR_DIMENSION; i++) {
            vector.add(i == axis ? 1.0f : 0.0f);
        }
        return vector;
    }
}
