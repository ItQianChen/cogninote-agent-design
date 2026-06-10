package com.itqianchen.agentdesign.repository.document;

import com.itqianchen.agentdesign.domain.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.document.FileType;
import com.itqianchen.agentdesign.domain.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.search.IndexStatistics;
import com.itqianchen.agentdesign.domain.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.search.IndexedDocument;
import com.itqianchen.agentdesign.domain.search.StoredChunk;
import com.itqianchen.agentdesign.mapper.document.DocumentMapper;
import com.itqianchen.agentdesign.mapper.document.IndexedDocumentRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * Document 仓储 是 文档管理 的持久化边界。
 * <p>服务层通过该类型访问数据，避免直接依赖 MyBatis Mapper 细节。</p>
 */
@Repository
public class DocumentRepository {

    private static final int CHUNK_INSERT_BATCH_SIZE = 200;
    private static final int MAX_STORED_CHUNK_LOOKUP_SIZE = 500;

    private final DocumentMapper documentMapper;

    /**
     * 注入 DocumentRepository 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public DocumentRepository(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    /**
     * 读取 find By Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public Optional<KnowledgeDocument> findById(String id) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findById(id).stream().findFirst();
    }

    /**
     * 读取 find All Order By Updated At Desc 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<KnowledgeDocument> findAllOrderByUpdatedAtDesc() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findAllOrderByUpdatedAtDesc();
    }

    /**
     * 读取 find Parsed Document For Indexing 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public Optional<IndexedDocument> findParsedDocumentForIndexing(String documentId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return mapIndexedDocuments(documentMapper.findParsedDocumentForIndexing(
                documentId,
                DocumentStatus.PARSED.name()
        )).stream().findFirst();
    }

    /**
     * 读取 find By Knowledge Folder Id Order By Updated At Desc 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<KnowledgeDocument> findByKnowledgeFolderIdOrderByUpdatedAtDesc(String knowledgeFolderId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findByKnowledgeFolderIdOrderByUpdatedAtDesc(knowledgeFolderId);
    }

    /**
     * 读取 find Unassigned Order By Updated At Desc 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<KnowledgeDocument> findUnassignedOrderByUpdatedAtDesc() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findUnassignedOrderByUpdatedAtDesc();
    }

    /**
     * 读取 find All Parsed Documents For Indexing 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<IndexedDocument> findAllParsedDocumentsForIndexing() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return mapIndexedDocuments(documentMapper.findAllParsedDocumentsForIndexing(DocumentStatus.PARSED.name()));
    }

    /**
     * 读取 find Parsed Documents For Indexing By Knowledge Folder Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<IndexedDocument> findParsedDocumentsForIndexingByKnowledgeFolderId(String knowledgeFolderId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return mapIndexedDocuments(documentMapper.findParsedDocumentsForIndexingByKnowledgeFolderId(
                DocumentStatus.PARSED.name(),
                knowledgeFolderId
        ));
    }

    /**
     * 读取 find Chunks By Document Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<KnowledgeChunk> findChunksByDocumentId(String documentId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findChunksByDocumentId(documentId);
    }

    /**
     * 读取 find Stored Chunks By Ids 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<StoredChunk> findStoredChunksByIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<String> lookupIds = chunkIds.size() > MAX_STORED_CHUNK_LOOKUP_SIZE
                ? chunkIds.subList(0, MAX_STORED_CHUNK_LOOKUP_SIZE)
                : chunkIds;
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
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
     * 读取 find Stored Chunk By Id 对应的数据。
     * <p>复用批量查询的 folder enabled 过滤规则，避免详情接口绕过知识库启用状态。</p>
     */
    public Optional<StoredChunk> findStoredChunkById(String chunkId) {
        return findStoredChunksByIds(List.of(chunkId)).stream().findFirst();
    }

    /**
     * 执行 文档管理 中的 upsert Document 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public void upsertDocument(KnowledgeDocument document) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.upsertDocument(document);
    }

    /**
     * 更新 update Knowledge Folder Id 对应的数据。
     * <p>方法负责保持内存快照、数据库记录和返回值语义一致。</p>
     */
    public void updateKnowledgeFolderId(String documentId, String knowledgeFolderId, long updatedAt) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.updateKnowledgeFolderId(documentId, knowledgeFolderId, updatedAt);
    }

    /**
     * 读取 find Document Ids By Knowledge Folder Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public List<String> findDocumentIdsByKnowledgeFolderId(String knowledgeFolderId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.findDocumentIdsByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 执行 文档管理 中的 mark Indexed 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public void markIndexed(String documentId, long indexedAt) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.markIndexed(documentId, indexedAt);
    }

    /**
     * 清理 clear Indexed 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearIndexed(String documentId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.clearIndexed(documentId);
    }

    /**
     * 清理 clear Indexed By Knowledge Folder Id 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearIndexedByKnowledgeFolderId(String knowledgeFolderId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.clearIndexedByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 执行 文档管理 中的 index Statistics 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public IndexStatistics indexStatistics() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.indexStatistics();
    }

    /**
     * 执行 文档管理 中的 replace Chunks 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public void replaceChunks(String documentId, List<KnowledgeChunk> chunks) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.deleteChunksByDocumentId(documentId);

        for (int start = 0; start < chunks.size(); start += CHUNK_INSERT_BATCH_SIZE) {
            int end = Math.min(start + CHUNK_INSERT_BATCH_SIZE, chunks.size());
            for (KnowledgeChunk chunk : chunks.subList(start, end)) {
                // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
                documentMapper.insertChunk(chunk);
            }
        }
    }

    /**
     * 删除 delete By Id 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    public boolean deleteById(String id) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.deleteChunksByDocumentId(id);
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.deleteDocumentById(id) > 0;
    }

    /**
     * 删除 delete By Knowledge Folder Id 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    public int deleteByKnowledgeFolderId(String knowledgeFolderId) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        documentMapper.deleteChunksByKnowledgeFolderId(knowledgeFolderId);
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        return documentMapper.deleteDocumentsByKnowledgeFolderId(knowledgeFolderId);
    }

    /**
     * 将输入映射为 map Indexed Documents 对应的业务分类。
     * <p>分类规则集中维护，避免调用方散落字符串或枚举判断。</p>
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

    /**
     * Indexed Document 构建器 负责构建 文档管理 相关的标准化对象。
     * <p>构建过程集中处理拼接、清洗和兼容规则。</p>
     */
    private static class IndexedDocumentBuilder {
        private final String id;
        private final String sourcePath;
        private final String fileName;
        private final FileType fileType;
        private final List<IndexedChunk> chunks = new ArrayList<>();

        /**
         * 执行 文档管理 中的 Indexed Document 构建器 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        private IndexedDocumentBuilder(String id, String sourcePath, String fileName, FileType fileType) {
            this.id = id;
            this.sourcePath = sourcePath;
            this.fileName = fileName;
            this.fileType = fileType;
        }

        /**
         * 构建 build 对象。
         * <p>第三方 API、框架对象或复杂参数的创建细节集中在此处。</p>
         */
        private IndexedDocument build() {
            return new IndexedDocument(id, sourcePath, fileName, fileType, List.copyOf(chunks));
        }
    }
}
