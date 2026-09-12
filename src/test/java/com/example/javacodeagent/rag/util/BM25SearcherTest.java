package com.example.javacodeagent.rag.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BM25 索引单测（Lucene SmartCN 分词，不触网）。
 *
 * 重点覆盖"多文档共存"：早期实现每次上传都 CREATE 重建索引，
 * 后上传的研报会把先前的研报从 BM25 里顶掉，这里把这个行为钉住。
 */
class BM25SearcherTest {

    @TempDir
    Path tempDir;

    private static List<String> hitIds(List<BM25Searcher.Bm25Result> results) {
        return results.stream().map(BM25Searcher.Bm25Result::chunkId).toList();
    }

    @Test
    void appendChunks_shouldKeepPreviousDocuments() {
        BM25Searcher searcher = new BM25Searcher(tempDir.resolve("append"));

        searcher.appendChunks(List.of(new BM25Searcher.Bm25Chunk("doc1-c1", "贵州茅台发布三季报，净利润同比增长15%")));
        searcher.appendChunks(List.of(new BM25Searcher.Bm25Chunk("doc2-c1", "宁德时代动力电池装机量继续提升")));

        assertTrue(hitIds(searcher.searchTopChunks("贵州茅台")).contains("doc1-c1"), "第一份文档仍应可检索");
        assertTrue(hitIds(searcher.searchTopChunks("宁德时代")).contains("doc2-c1"), "第二份文档应可检索");
    }

    @Test
    void createOrReplaceIndex_shouldDropOldChunks() {
        BM25Searcher searcher = new BM25Searcher(tempDir.resolve("replace"));
        searcher.appendChunks(List.of(new BM25Searcher.Bm25Chunk("old", "旧文档：光伏组件价格触底反弹")));

        searcher.createOrReplaceIndex(List.of(new BM25Searcher.Bm25Chunk("new", "新文档：储能招标规模超预期")));

        assertFalse(hitIds(searcher.searchTopChunks("光伏组件")).contains("old"), "重建后旧 chunk 不应残留");
        assertTrue(hitIds(searcher.searchTopChunks("储能招标")).contains("new"));
    }

    @Test
    void searchOnMissingIndex_shouldReturnEmptyInsteadOfThrowing() {
        BM25Searcher searcher = new BM25Searcher(tempDir.resolve("not-built-yet"));

        assertTrue(searcher.searchTopChunks("任意查询").isEmpty());
    }

    @Test
    void blankQuery_shouldReturnEmpty() {
        BM25Searcher searcher = new BM25Searcher(tempDir.resolve("blank"));
        searcher.appendChunks(List.of(new BM25Searcher.Bm25Chunk("c1", "贵州茅台")));

        assertTrue(searcher.searchTopChunks("  ").isEmpty());
        assertTrue(searcher.searchTopChunks(null).isEmpty());
    }
}
