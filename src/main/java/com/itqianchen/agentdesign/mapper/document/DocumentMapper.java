package com.itqianchen.agentdesign.mapper.document;


import com.itqianchen.agentdesign.domain.vo.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.vo.search.IndexStatistics;
import com.itqianchen.agentdesign.domain.vo.search.StoredChunk;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 文档、chunk 和索引状态的 MyBatis SQL 边界。
 *
 * <p>SQLite 是文档解析结果的事实来源，Lucene 只从这里读取可重建快照；该 Mapper 不直接操作索引文件。</p>
 */
public interface DocumentMapper {

    /**
     * 按 ID 查询文档。
     *
     * @param id 文档 ID
     * @return 文档记录
     */
    List<KnowledgeDocument> findById(@Param("id") String id);

    /**
     * 查询全部文档摘要。
     *
     * @return 按更新时间倒序排列的文档列表
     */
    List<KnowledgeDocument> findAllOrderByUpdatedAtDesc();

    /**
     * 返回索引重建所需的 document x chunk 扁平行。
     *
     * <p>Repository 会按 documentId 聚合成 IndexedDocument，Mapper 保持 SQL 结果简单可控。</p>
     *
     * @param documentId 文档 ID
     * @param status 允许进入索引的文档状态
     * @return 文档与 chunk 的扁平行
     */
    List<IndexedDocumentRow> findParsedDocumentForIndexing(
            @Param("documentId") String documentId,
            @Param("status") String status
    );

    /**
     * 查询指定目录下的文档。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 按更新时间倒序排列的文档列表
     */
    List<KnowledgeDocument> findByKnowledgeFolderIdOrderByUpdatedAtDesc(
            @Param("knowledgeFolderId") String knowledgeFolderId
    );

    /**
     * 查询未绑定知识库目录的文档。
     *
     * @return 未归属文档列表
     */
    List<KnowledgeDocument> findUnassignedOrderByUpdatedAtDesc();

    /**
     * 查询所有可索引文档的扁平快照。
     *
     * @param status 允许进入索引的文档状态
     * @return 文档与 chunk 的扁平行
     */
    List<IndexedDocumentRow> findAllParsedDocumentsForIndexing(@Param("status") String status);

    /**
     * 查询所有已解析但未进入索引的文档快照。
     *
     * @param status 允许进入索引的文档状态
     * @return 文档与 chunk 的扁平行
     */
    List<IndexedDocumentRow> findUnindexedParsedDocumentsForIndexing(@Param("status") String status);

    /**
     * 只读取指定目录下可索引的文档快照。
     *
     * <p>目录启用和目录重建依赖该查询，不能包含已失败或未解析完成的文档。</p>
     *
     * @param status 允许进入索引的文档状态
     * @param knowledgeFolderId 知识库目录 ID
     * @return 文档与 chunk 的扁平行
     */
    List<IndexedDocumentRow> findParsedDocumentsForIndexingByKnowledgeFolderId(
            @Param("status") String status,
            @Param("knowledgeFolderId") String knowledgeFolderId
    );

    /**
     * 只读取指定目录下已解析但未进入索引的文档快照。
     *
     * @param status 允许进入索引的文档状态
     * @param knowledgeFolderId 知识库目录 ID
     * @return 文档与 chunk 的扁平行
     */
    List<IndexedDocumentRow> findUnindexedParsedDocumentsForIndexingByKnowledgeFolderId(
            @Param("status") String status,
            @Param("knowledgeFolderId") String knowledgeFolderId
    );

    /**
     * 查询文档 chunk。
     *
     * @param documentId 文档 ID
     * @return chunk 列表
     */
    List<KnowledgeChunk> findChunksByDocumentId(@Param("documentId") String documentId);

    /**
     * 批量查询来源展示需要的 chunk 详情。
     *
     * @param chunkIds chunk ID 列表
     * @return 已找到的 chunk 详情
     */
    List<StoredChunk> findStoredChunksByIds(@Param("chunkIds") List<String> chunkIds);

    /**
     * 新增或更新文档记录。
     *
     * @param document 文档领域对象
     */
    void upsertDocument(KnowledgeDocument document);

    void updateLastFailure(
            @Param("documentId") String documentId,
            @Param("stage") String stage,
            @Param("code") String code,
            @Param("message") String message,
            @Param("detail") String detail,
            @Param("contextJson") String contextJson,
            @Param("failedAt") long failedAt,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 更新文档所属知识库目录。
     *
     * @param documentId 文档 ID
     * @param knowledgeFolderId 知识库目录 ID；null 表示解除归属
     * @param updatedAt 更新时间戳
     */
    void updateKnowledgeFolderId(
            @Param("documentId") String documentId,
            @Param("knowledgeFolderId") String knowledgeFolderId,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 查询目录下全部文档 ID。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 文档 ID 列表
     */
    List<String> findDocumentIdsByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    /**
     * 标记文档已完成索引。
     *
     * @param documentId 文档 ID
     * @param indexedAt 索引完成时间戳
     */
    void markIndexed(@Param("documentId") String documentId, @Param("indexedAt") long indexedAt);

    /**
     * 清除单个文档的索引时间。
     *
     * @param documentId 文档 ID
     */
    void clearIndexed(@Param("documentId") String documentId);

    /**
     * 清除目录下文档的索引时间。
     *
     * @param knowledgeFolderId 知识库目录 ID
     */
    void clearIndexedByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    /**
     * 统计文档、chunk 和索引完成情况。
     *
     * @return 索引统计
     */
    IndexStatistics indexStatistics();

    /**
     * 删除单个文档下的 chunk。
     *
     * @param documentId 文档 ID
     */
    void deleteChunksByDocumentId(@Param("documentId") String documentId);

    /**
     * 插入单个 chunk。
     *
     * @param chunk 文档 chunk
     */
    void insertChunk(KnowledgeChunk chunk);

    /**
     * 删除文档主记录。
     *
     * <p>调用方会先显式删除 chunks，避免不同 SQLite 外键配置下出现孤儿片段。</p>
     *
     * @param id 文档 ID
     * @return 受影响行数
     */
    int deleteDocumentById(@Param("id") String id);

    /**
     * 删除目录下所有文档 chunk。
     *
     * @param knowledgeFolderId 知识库目录 ID
     */
    void deleteChunksByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    /**
     * 删除目录下所有文档记录。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 删除的文档数量
     */
    int deleteDocumentsByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

}
