package com.example.javacodeagent.rag;

import java.nio.file.Path;

/**
 * RAG 相关磁盘路径的唯一来源，避免同一份数据在多处硬编码出不同目录。
 *
 * <p>目前有两套互不干扰的检索数据：</p>
 * <ul>
 *   <li>{@code rag-knowledge/}：投研知识库（结构化条目 + 通用规则），随代码入库，
 *       由 {@code KnowledgeBaseService} 独占读写，检索结果以【参考知识】显式注入 Prompt。</li>
 *   <li>{@code rag-docs/}：用户上传的研报文档（原始文件 + 向量 + BM25），属于运行时数据，
 *       由 {@code DocumentService} 写入，经 LangChain4j {@code ContentRetriever} 自动召回。</li>
 * </ul>
 */
public final class RagPaths {

    /** 上传文档的根目录（运行时数据，不入库）。 */
    public static final String DOCS_DIR = "rag-docs";

    /** 上传文档的向量存储持久化文件。 */
    public static final Path DOCS_VECTOR_STORE = Path.of(DOCS_DIR, "vector-store.json");

    /** 上传文档的 BM25 索引目录。 */
    public static final Path DOCS_BM25_INDEX = Path.of(DOCS_DIR, "bm25-index");

    /** 上传文档的清单文件。 */
    public static final Path DOCS_MANIFEST = Path.of(DOCS_DIR, "manifest.json");

    private RagPaths() {
    }
}
