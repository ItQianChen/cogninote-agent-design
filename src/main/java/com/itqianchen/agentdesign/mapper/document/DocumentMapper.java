package com.itqianchen.agentdesign.mapper.document;

import com.itqianchen.agentdesign.domain.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.search.IndexStatistics;
import com.itqianchen.agentdesign.domain.search.StoredChunk;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface DocumentMapper {

    List<KnowledgeDocument> findById(@Param("id") String id);

    List<KnowledgeDocument> findAllOrderByUpdatedAtDesc();

    List<IndexedDocumentRow> findParsedDocumentForIndexing(
            @Param("documentId") String documentId,
            @Param("status") String status
    );

    List<KnowledgeDocument> findByKnowledgeFolderIdOrderByUpdatedAtDesc(
            @Param("knowledgeFolderId") String knowledgeFolderId
    );

    List<KnowledgeDocument> findUnassignedOrderByUpdatedAtDesc();

    List<IndexedDocumentRow> findAllParsedDocumentsForIndexing(@Param("status") String status);

    List<IndexedDocumentRow> findParsedDocumentsForIndexingByKnowledgeFolderId(
            @Param("status") String status,
            @Param("knowledgeFolderId") String knowledgeFolderId
    );

    List<KnowledgeChunk> findChunksByDocumentId(@Param("documentId") String documentId);

    List<StoredChunk> findStoredChunksByIds(@Param("chunkIds") List<String> chunkIds);

    void upsertDocument(KnowledgeDocument document);

    void updateKnowledgeFolderId(
            @Param("documentId") String documentId,
            @Param("knowledgeFolderId") String knowledgeFolderId,
            @Param("updatedAt") long updatedAt
    );

    List<String> findDocumentIdsByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    void markIndexed(@Param("documentId") String documentId, @Param("indexedAt") long indexedAt);

    void clearIndexed(@Param("documentId") String documentId);

    void clearIndexedByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    IndexStatistics indexStatistics();

    void deleteChunksByDocumentId(@Param("documentId") String documentId);

    void insertChunk(KnowledgeChunk chunk);

    int deleteDocumentById(@Param("id") String id);

    void deleteChunksByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

    int deleteDocumentsByKnowledgeFolderId(@Param("knowledgeFolderId") String knowledgeFolderId);

}
