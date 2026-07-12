package com.itqianchen.agentdesign.repository.document;

import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.vo.search.IndexStatistics;
import com.itqianchen.agentdesign.domain.vo.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.vo.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.vo.search.StoredChunk;
import com.itqianchen.agentdesign.mapper.document.DocumentMapper;
import com.itqianchen.agentdesign.mapper.document.IndexedDocumentRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 文档和 chunk 的仓储边界。
 *
 * <p>SQLite 中的解析结果是索引重建的事实来源，Lucene 删除或重建失败时都应回到这里重新收敛。</p>
 */
@Repository
public class DocumentRepository {

    private static final int CHUNK_INSERT_BATCH_SIZE = 200;
    private static final int MAX_STORED_CHUNK_LOOKUP_SIZE = 500;

    private final DocumentMapper documentMapper;

    /**
     * 注入文档 Mapper。
     *
     * @param documentMapper SQLite 文档和 chunk 访问接口
     */
    public DocumentRepository(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /**
     * 按 ID 查询文档元数据。
     *
     * @param id 文档 ID
     * @return 文档元数据；不存在时为空
     */
    public Optional<KnowledgeDocument> findById(String id) {
        return documentMapper.findById(id).stream().findFirst();
    }

    /**
     * 查询所有文档元数据。
     *
     * @return 按更新时间倒序排列的文档列表
     */
    public List<KnowledgeDocument> findAllOrderByUpdatedAtDesc() {
        return documentMapper.findAllOrderByUpdatedAtDesc();
    }

    /**
     * 查询单个已解析文档的索引快照。
     *
     * <p>只返回 PARSED 状态文档，避免失败或处理中数据写入 Lucene。</p>
     *
     * @param documentId 文档 ID
     * @return 可索引文档聚合
     */
    public Optional<IndexedDocument> findParsedDocumentForIndexing(String documentId) {
        return mapIndexedDocuments(documentMapper.findParsedDocumentForIndexing(
                documentId,
                DocumentStatus.PARSED.name()
        )).stream().findFirst();
    }

    /**
     * 查询指定知识库目录下的文档。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 按更新时间倒序排列的文档列表
     */
    public List<KnowledgeDocument> findByKnowledgeFolderIdOrderByUpdatedAtDesc(String knowledgeFolderId) {
        return documentMapper.findByKnowledgeFolderIdOrderByUpdatedAtDesc(knowledgeFolderId);
    }

    /**
     * 查询未归属任何知识库目录的文档。
     *
     * @return 未归属文档列表
     */
    public List<KnowledgeDocument> findUnassignedOrderByUpdatedAtDesc() {
        return documentMapper.findUnassignedOrderByUpdatedAtDesc();
    }

    /**
     * 查询全部已解析文档的索引快照。
     *
     * @return 可用于全量重建索引的文档聚合列表
     */
    public List<IndexedDocument> findAllParsedDocumentsForIndexing() {
        return mapIndexedDocuments(documentMapper.findAllParsedDocumentsForIndexing(DocumentStatus.PARSED.name()));
    }

    /**
     * 查询全部已解析但未索引文档的索引快照。
     *
     * @return 可用于补写索引的文档聚合列表
     */
    public List<IndexedDocument> findUnindexedParsedDocumentsForIndexing() {
        return mapIndexedDocuments(documentMapper.findUnindexedParsedDocumentsForIndexing(DocumentStatus.PARSED.name()));
    }

    /**
     * 查询指定知识库目录下已解析文档的索引快照。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 可用于目录重建索引的文档聚合列表
     */
    public List<IndexedDocument> findParsedDocumentsForIndexingByKnowledgeFolderId(String knowledgeFolderId) {
        return mapIndexedDocuments(documentMapper.findParsedDocumentsForIndexingByKnowledgeFolderId(
                DocumentStatus.PARSED.name(),
                knowledgeFolderId
        ));
    }

    /**
     * 查询指定目录下已解析但未索引文档的索引快照。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 可用于目录补写索引的文档聚合列表
     */
    public List<IndexedDocument> findUnindexedParsedDocumentsForIndexingByKnowledgeFolderId(String knowledgeFolderId) {
        return mapIndexedDocuments(documentMapper.findUnindexedParsedDocumentsForIndexingByKnowledgeFolderId(
                DocumentStatus.PARSED.name(),
                knowledgeFolderId
        ));
    }

    /**
     * 查询文档的全部 chunk。
     *
     * @param documentId 文档 ID
     * @return 文档 chunk 列表
     */
    public List<KnowledgeChunk> findChunksByDocumentId(String documentId) {
        return documentMapper.findChunksByDocumentId(documentId);
    }

    /**
     * 按 chunkId 批量读取已存储片段。
     *
     * <p>返回顺序尽量跟随输入 ID，且限制单次 IN 查询规模，避免来源抽屉加载异常拖慢 SQLite。</p>
     *
     * @param chunkIds 待查询的 chunkId 列表
     * @return 已找到的片段，缺失 ID 会被忽略
     */
    public List<StoredChunk> findStoredChunksByIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<String> lookupIds = chunkIds.size() > MAX_STORED_CHUNK_LOOKUP_SIZE
                ? chunkIds.subList(0, MAX_STORED_CHUNK_LOOKUP_SIZE)
                : chunkIds;
        // 来源抽屉一次只展示有限片段，限制 IN 查询规模避免极端搜索结果拖慢 SQLite。
        List<StoredChunk> chunks = documentMapper.findStoredChunksByIds(lookupIds);
        Map<String, StoredChunk> byId = new LinkedHashMap<>();
        for (StoredChunk chunk : chunks) {
            byId.put(chunk.chunkId(), chunk);
        }

        return lookupIds.stream()
                .map(byId::get)
                .filter(chunk -> chunk != null)
                .toList();
    }

    /**
     * 按 chunkId 查询单个已存储片段。
     *
     * @param chunkId 文档片段 ID
     * @return 片段详情；不存在时为空
     */
    public Optional<StoredChunk> findStoredChunkById(String chunkId) {
        return findStoredChunksByIds(List.of(chunkId)).stream().findFirst();
    }

    /**
     * 新增或更新文档元数据。
     *
     * <p>该方法不处理 chunk，调用方必须在解析成功后通过 replaceChunks 同步完整片段集合。</p>
     *
     * @param document 文档元数据
     */
    public void upsertDocument(KnowledgeDocument document) {
        documentMapper.upsertDocument(document);
    }

    /** 保存文档最近一次处理失败，同时保留当前状态、chunk 和索引时间。 */
    public void updateLastFailure(
            String documentId,
            DocumentFailureResponse failure,
            String contextJson,
            long updatedAt
    ) {
        documentMapper.updateLastFailure(
                documentId,
                failure.stage(),
                failure.code(),
                failure.message(),
                failure.detail(),
                contextJson,
                failure.occurredAt() == null ? updatedAt : failure.occurredAt(),
                updatedAt
        );
    }

    /**
     * 更新文档所属知识库目录。
     *
     * @param documentId 文档 ID
     * @param knowledgeFolderId 新的目录 ID；可为空表示解除归属
     * @param updatedAt 更新时间戳
     */
    public void updateKnowledgeFolderId(String documentId, String knowledgeFolderId, long updatedAt) {
        documentMapper.updateKnowledgeFolderId(documentId, knowledgeFolderId, updatedAt);
    }

    /**
     * 查询目录下所有文档 ID。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 文档 ID 列表
     */
    public List<String> findDocumentIdsByKnowledgeFolderId(String knowledgeFolderId) {
        return documentMapper.findDocumentIdsByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 标记文档已写入检索索引。
     *
     * @param documentId 文档 ID
     * @param indexedAt 索引完成时间戳
     */
    public void markIndexed(String documentId, long indexedAt) {
        documentMapper.markIndexed(documentId, indexedAt);
    }

    /**
     * 清除单个文档的索引时间。
     *
     * <p>调用方在删除或重建 Lucene 索引后使用该标记表达“需要重新索引”。</p>
     *
     * @param documentId 文档 ID
     */
    public void clearIndexed(String documentId) {
        documentMapper.clearIndexed(documentId);
    }

    /**
     * 清除目录下文档的索引时间。
     *
     * @param knowledgeFolderId 知识库目录 ID
     */
    public void clearIndexedByKnowledgeFolderId(String knowledgeFolderId) {
        documentMapper.clearIndexedByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 读取索引状态统计。
     *
     * @return 文档、chunk 和未索引数量统计
     */
    public IndexStatistics indexStatistics() {
        return documentMapper.indexStatistics();
    }

    /**
     * 用当前解析结果完整替换文档 chunk。
     *
     * <p>先删后写可以清除旧文件残留片段；批量大小受 SQLite 单次写入和测试稳定性约束。</p>
     *
     * @param documentId 文档 ID
     * @param chunks 新的完整 chunk 集合
     */
    public void replaceChunks(String documentId, List<KnowledgeChunk> chunks) {
        // chunk 是文档当前解析结果的完整替换，先删后写可清除旧文件残留片段。
        documentMapper.deleteChunksByDocumentId(documentId);

        for (int start = 0; start < chunks.size(); start += CHUNK_INSERT_BATCH_SIZE) {
            int end = Math.min(start + CHUNK_INSERT_BATCH_SIZE, chunks.size());
            for (KnowledgeChunk chunk : chunks.subList(start, end)) {
                documentMapper.insertChunk(chunk);
            }
        }
    }

    /**
     * 删除文档及其 chunk。
     *
     * @param id 文档 ID
     * @return 是否删除了文档记录
     */
    public boolean deleteById(String id) {
        // SQLite 外键开关在不同运行环境可能不一致，显式先删 chunks 防止孤儿片段。
        documentMapper.deleteChunksByDocumentId(id);
        return documentMapper.deleteDocumentById(id) > 0;
    }

    /**
     * 删除知识库目录下的应用内文档元数据。
     *
     * <p>只删除 SQLite 中的解析结果和 chunk，不触碰用户本地原始文件。</p>
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @return 删除的文档数量
     */
    public int deleteByKnowledgeFolderId(String knowledgeFolderId) {
        // 删除目录时只清应用内文档和 chunk 元数据，不触碰用户文件系统。
        documentMapper.deleteChunksByKnowledgeFolderId(knowledgeFolderId);
        return documentMapper.deleteDocumentsByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 将 mapper 的 document x chunk 扁平行恢复为索引写入需要的文档聚合。
     *
     * <p>聚合顺序沿用 SQL 返回顺序，便于 Lucene 重建时保持 chunk 顺序稳定。</p>
     */
    private static List<IndexedDocument> mapIndexedDocuments(List<IndexedDocumentRow> rows) {
        Map<String, IndexedDocumentBuilder> documents = new LinkedHashMap<>();
        for (IndexedDocumentRow row : rows) {
            IndexedDocumentBuilder builder = documents.computeIfAbsent(
                    row.documentId(),
                    ignored -> new IndexedDocumentBuilder(
                            row.documentId(),
                            row.sourcePath(),
                            row.fileName(),
                            row.fileType()
                    )
            );
            builder.chunks.add(new IndexedChunk(
                    row.chunkId(),
                    row.documentId(),
                    row.chunkIndex(),
                    row.content(),
                    row.contentHash(),
                    row.pageNumber(),
                    row.heading()
            ));
        }

        return documents.values().stream()
                .map(IndexedDocumentBuilder::build)
                .toList();
    }

    private static class IndexedDocumentBuilder {
        private final String id;
        private final String sourcePath;
        private final String fileName;
        private final FileType fileType;
        private final List<IndexedChunk> chunks = new ArrayList<>();

        /**
         * 保留文档元数据并收集 chunk 行。
         *
         * @param id 文档 ID
         * @param sourcePath 原始文件路径
         * @param fileName 文件名
         * @param fileType 文件类型
         */
        private IndexedDocumentBuilder(String id, String sourcePath, String fileName, FileType fileType) {
            this.id = id;
            this.sourcePath = sourcePath;
            this.fileName = fileName;
            this.fileType = fileType;
        }

        /**
         * 构建不可变的索引文档快照。
         *
         * @return 索引文档
         */
        private IndexedDocument build() {
            return new IndexedDocument(id, sourcePath, fileName, fileType, List.copyOf(chunks));
        }
    }
}
