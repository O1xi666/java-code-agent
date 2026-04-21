package com.example.javacodeagent.rag.service;

import com.alibaba.fastjson.JSONObject;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.DescribeCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.collection.request.LoadCollectionReq;
import io.milvus.v2.service.index.request.CreateIndexReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.response.SearchResp;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Milvus 向量服务：
 * <ul>
 *     <li>集合创建（chunk_id / content / source / token_count / vector）</li>
 *     <li>IVF_FLAT 索引创建</li>
 *     <li>批量向量入库（nomic-embed-text 的 1024 维向量）</li>
 *     <li>向量检索（Top20）</li>
 * </ul>
 *
 * <p>说明：本类通过注入 {@link MilvusClientV2} 工作，该 Bean 由 rag.config 下的 MilvusConfig 提供。</p>
 */
@Service
public class MilvusVectorService {

    /**
     * 默认集合名（可按需抽到配置文件）。
     */
    public static final String COLLECTION_NAME = "rag_chunk_collection";

    /**
     * 字段名定义，避免魔法字符串散落。
     */
    public static final String FIELD_CHUNK_ID = "chunk_id";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SOURCE = "source";
    public static final String FIELD_TOKEN_COUNT = "token_count";
    public static final String FIELD_VECTOR = "vector";

    /**
     * 你的 embedding 模型 nomic-embed-text 输出维度：1024。
     */
    public static final int VECTOR_DIMENSION = 1024;

    /**
     * IVF_FLAT 索引参数：聚类中心数量 nlist。
     */
    public static final int INDEX_NLIST = 1024;

    /**
     * 检索返回条数上限。
     */
    public static final int TOP_K = 20;

    /**
     * 检索时 nprobe，控制查询时扫描多少个倒排桶。
     */
    public static final int SEARCH_NPROBE = 16;

    private final MilvusClientV2 milvusClient;

    public MilvusVectorService(MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 初始化集合与索引：
     * - 若集合不存在则创建
     * - 创建 IVF_FLAT 索引
     * - 加载集合，确保后续可直接检索
     */
    public void initCollectionIfAbsent() {
        boolean exists = milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build()
        );
        if (!exists) {
            createCollection();
            createVectorIndex();
        }

        milvusClient.loadCollection(
                LoadCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build()
        );
    }

    /**
     * 批量向量入库。
     *
     * @param records 待入库记录，每条记录包含 1024 维向量与 chunk 信息
     */
    public void insertBatch(List<VectorRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        List<JSONObject> rows = new ArrayList<>(records.size());
        for (VectorRecord record : records) {
            validateVector(record.vector());

            JSONObject row = new JSONObject();
            row.put(FIELD_CHUNK_ID, normalizeChunkId(record.chunkId()));
            row.put(FIELD_CONTENT, record.content());
            row.put(FIELD_SOURCE, record.source());
            row.put(FIELD_TOKEN_COUNT, record.tokenCount());
            row.put(FIELD_VECTOR, record.vector());
            rows.add(row);
        }

        milvusClient.insert(
                InsertReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .data(rows)
                        .build()
        );
    }

    /**
     * 向量检索（Top20）。
     *
     * @param queryVector 查询向量（1024 维）
     * @return 相似 chunk 列表（按 Milvus 返回顺序）
     */
    public List<SimilarChunk> searchTopChunks(List<Float> queryVector) {
        validateVector(queryVector);

        SearchResp response = milvusClient.search(
                SearchReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .annsField(FIELD_VECTOR)
                        .topK(TOP_K)
                        .data(List.of(queryVector))
                        .outputFields(List.of(FIELD_CHUNK_ID, FIELD_CONTENT, FIELD_SOURCE, FIELD_TOKEN_COUNT))
                        .searchParams(Map.of(
                                "metric_type", "COSINE",
                                "params", Map.of("nprobe", SEARCH_NPROBE)
                        ))
                        .build()
        );

        List<List<SearchResp.SearchResult>> allResults = response.getSearchResults();
        if (allResults == null || allResults.isEmpty()) {
            return List.of();
        }

        List<SearchResp.SearchResult> firstQueryResults = allResults.getFirst();
        List<SimilarChunk> results = new ArrayList<>(firstQueryResults.size());
        for (SearchResp.SearchResult result : firstQueryResults) {
            Map<String, Object> entity = result.getEntity();
            if (entity == null) {
                continue;
            }

            results.add(new SimilarChunk(
                    Objects.toString(entity.get(FIELD_CHUNK_ID), ""),
                    Objects.toString(entity.get(FIELD_CONTENT), ""),
                    Objects.toString(entity.get(FIELD_SOURCE), ""),
                    toInt(entity.get(FIELD_TOKEN_COUNT)),
                    result.getDistance() == null ? 0.0f : result.getDistance()
            ));
        }
        return results;
    }

    /**
     * 真正执行集合创建，字段满足你的约束：
     * - vector (FloatVector, 1024)
     * - chunk_id (VarChar, 主键)
     * - content (VarChar)
     * - source (VarChar)
     * - token_count (Int32)
     */
    private void createCollection() {
        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CHUNK_ID)
                .dataType(DataType.VarChar)
                .isPrimaryKey(true)
                .autoID(false)
                .maxLength(128)
                .description("Chunk primary key")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_CONTENT)
                .dataType(DataType.VarChar)
                .maxLength(65535)
                .description("Original chunk text")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_SOURCE)
                .dataType(DataType.VarChar)
                .maxLength(512)
                .description("Document source")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_TOKEN_COUNT)
                .dataType(DataType.Int32)
                .description("Token count")
                .build());

        schema.addField(AddFieldReq.builder()
                .fieldName(FIELD_VECTOR)
                .dataType(DataType.FloatVector)
                .dimension(VECTOR_DIMENSION)
                .description("Embedding vector")
                .build());

        milvusClient.createCollection(CreateCollectionReq.builder()
                .collectionName(COLLECTION_NAME)
                .description("RAG chunks with embeddings")
                .collectionSchema(schema)
                .build());
    }

    /**
     * 创建 IVF_FLAT 索引。
     */
    private void createVectorIndex() {
        IndexParam vectorIndex = IndexParam.builder()
                .fieldName(FIELD_VECTOR)
                .indexName("idx_" + FIELD_VECTOR + "_ivf_flat")
                .indexType(IndexParam.IndexType.IVF_FLAT)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("nlist", INDEX_NLIST))
                .build();

        milvusClient.createIndex(CreateIndexReq.builder()
                .collectionName(COLLECTION_NAME)
                .indexParams(List.of(vectorIndex))
                .build());
    }

    /**
     * 可选：简单健康检查，确认集合可访问。
     */
    public boolean collectionReady() {
        return milvusClient.hasCollection(
                HasCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build()
        ) && milvusClient.describeCollection(
                DescribeCollectionReq.builder()
                        .collectionName(COLLECTION_NAME)
                        .build()
        ) != null;
    }

    private static void validateVector(List<Float> vector) {
        if (vector == null || vector.size() != VECTOR_DIMENSION) {
            throw new IllegalArgumentException(
                    "Vector dimension mismatch, expected " + VECTOR_DIMENSION + " but got "
                            + (vector == null ? 0 : vector.size())
            );
        }
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static String normalizeChunkId(String rawChunkId) {
        if (rawChunkId == null || rawChunkId.isBlank()) {
            return "chunk-" + UUID.randomUUID();
        }
        return rawChunkId;
    }

    /**
     * 入库记录模型。
     */
    public record VectorRecord(
            String chunkId,
            String content,
            String source,
            int tokenCount,
            List<Float> vector
    ) {
    }

    /**
     * 检索结果模型。
     */
    public record SimilarChunk(
            String chunkId,
            String content,
            String source,
            int tokenCount,
            float score
    ) {
    }
}
