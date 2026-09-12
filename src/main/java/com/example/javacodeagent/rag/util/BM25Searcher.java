package com.example.javacodeagent.rag.util;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * BM25 检索工具（Lucene 9.x + SmartChineseAnalyzer）。
 * <p>
 * 主要能力：
 * <ul>
 *     <li>构建/重建 BM25 索引并持久化到磁盘目录</li>
 *     <li>按查询词执行 BM25 检索，返回 Top20 结果</li>
 * </ul>
 *
 * <p>注意：该类仅做本地稀疏检索，适合作为混合检索（向量+BM25）中的 BM25 子模块。</p>
 */
public class BM25Searcher {

    /**
     * 索引字段：chunk 唯一标识。
     */
    public static final String FIELD_CHUNK_ID = "chunk_id";

    /**
     * 索引字段：chunk 原文内容（用于分词和检索）。
     */
    public static final String FIELD_CONTENT = "content";

    /**
     * 默认返回条数。
     */
    public static final int TOP_K = 20;

    /**
     * 索引目录（磁盘持久化路径）。
     */
    private final Path indexPath;

    /**
     * 中文分词器（Lucene SmartCN）。
     */
    private final Analyzer analyzer;

    public BM25Searcher(Path indexPath) {
        this.indexPath = indexPath;
        this.analyzer = new SmartChineseAnalyzer();
    }

    /**
     * 创建（或重建）BM25索引。
     * <p>
     * 采用 OpenMode.CREATE：每次调用会覆盖旧索引，适合全量重建场景。
     *
     * @param chunks 待索引的 chunk 数据
     */
    public void createOrReplaceIndex(List<Bm25Chunk> chunks) {
        writeChunks(chunks, IndexWriterConfig.OpenMode.CREATE);
    }

    /**
     * 增量追加 chunk 到现有索引（不存在时自动创建）。
     * <p>多文档场景必须用追加而不是重建，否则后上传的文档会把先前文档的索引覆盖掉。</p>
     *
     * @param chunks 待追加的 chunk 数据
     */
    public void appendChunks(List<Bm25Chunk> chunks) {
        writeChunks(chunks, IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
    }

    private void writeChunks(List<Bm25Chunk> chunks, IndexWriterConfig.OpenMode openMode) {
        if (chunks == null) {
            throw new IllegalArgumentException("chunks must not be null");
        }

        try {
            Files.createDirectories(indexPath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create index directory: " + indexPath, e);
        }

        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(openMode);
        config.setSimilarity(new BM25Similarity());

        try (Directory directory = FSDirectory.open(indexPath);
             IndexWriter writer = new IndexWriter(directory, config)) {

            for (Bm25Chunk chunk : chunks) {
                if (chunk == null || chunk.chunkId() == null || chunk.content() == null) {
                    continue;
                }

                Document doc = new Document();
                // chunk_id 作为精确字段存储，便于返回和后续定位。
                doc.add(new StringField(FIELD_CHUNK_ID, chunk.chunkId(), Field.Store.YES));
                // content 同时用于索引和返回（Store.YES 方便直接取原文）。
                doc.add(new TextField(FIELD_CONTENT, chunk.content(), Field.Store.YES));
                writer.addDocument(doc);
            }

            writer.commit();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build BM25 index", e);
        }
    }

    /**
     * 执行 BM25 检索，默认返回 Top20。
     *
     * @param queryText 查询词/查询句
     * @return 命中的 chunk 结果（包含 chunk_id、原文、相关性得分）
     */
    public List<Bm25Result> searchTopChunks(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            return List.of();
        }

        if (!Files.exists(indexPath)) {
            return List.of();
        }

        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                return List.of();
            }

            try (DirectoryReader reader = DirectoryReader.open(directory)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                searcher.setSimilarity(new BM25Similarity());

                Query query = buildQueryWithSmartCn(queryText);

                TopDocs topDocs = searcher.search(query, TOP_K);
                List<Bm25Result> results = new ArrayList<>(topDocs.scoreDocs.length);
                for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                    Document doc = searcher.doc(scoreDoc.doc);
                    results.add(new Bm25Result(
                            doc.get(FIELD_CHUNK_ID),
                            doc.get(FIELD_CONTENT),
                            scoreDoc.score
                    ));
                }
                return results;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to search BM25 index", e);
        }
    }

    /**
     * 关闭分词器资源。
     * <p>如果你在应用退出时需要主动释放资源，可调用该方法。</p>
     */
    public void close() {
        analyzer.close();
    }

    /**
     * 使用 SmartCN 对查询文本分词，然后拼装为 SHOULD 条件的 BooleanQuery。
     * <p>
     * 这样可以避免额外依赖 queryparser 模块，同时仍保留中文分词能力。
     */
    private Query buildQueryWithSmartCn(String queryText) throws IOException {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();

        try (TokenStream tokenStream = analyzer.tokenStream(FIELD_CONTENT, new StringReader(queryText))) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String term = termAttribute.toString();
                if (!term.isBlank()) {
                    builder.add(new TermQuery(new Term(FIELD_CONTENT, term)), BooleanClause.Occur.SHOULD);
                }
            }
            tokenStream.end();
        }

        BooleanQuery booleanQuery = builder.build();
        // 如果分词后为空，回退为全量不命中的兜底查询。
        if (booleanQuery.clauses().isEmpty()) {
            return new TermQuery(new Term(FIELD_CONTENT, "__no_match__"));
        }
        return booleanQuery;
    }

    /**
     * 建索引输入模型。
     *
     * @param chunkId chunk 唯一标识
     * @param content chunk 原文
     */
    public record Bm25Chunk(String chunkId, String content) {
    }

    /**
     * 检索输出模型。
     *
     * @param chunkId chunk 唯一标识
     * @param content chunk 原文
     * @param score   BM25 相关性得分（越大越相关）
     */
    public record Bm25Result(String chunkId, String content, float score) {
    }
}
