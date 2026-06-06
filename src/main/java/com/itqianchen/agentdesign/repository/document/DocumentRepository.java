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

@Repository
public class DocumentRepository {

    private static final int CHUNK_INSERT_BATCH_SIZE = 200;
    private static final int MAX_STORED_CHUNK_LOOKUP_SIZE = 500;

    private final DocumentMapper documentMapper;

    public DocumentRepository(DocumentMapper documentMapper) {
        this.documentMapper = documentMapper;
    }

    public Optional<KnowledgeDocument> findById(String id) {
        return documentMapper.findById(id).stream().findFirst();
    }

    public List<KnowledgeDocument> findAllOrderByUpdatedAtDesc() {
        return documentMapper.findAllOrderByUpdatedAtDesc();
    }

    public Optional<IndexedDocument> findParsedDocumentForIndexing(String documentId) {
        return mapIndexedDocuments(documentMapper.findParsedDocumentForIndexing(
                documentId,
                DocumentStatus.PARSED.name()
        )).stream().findFirst();
    }

    public List<KnowledgeDocument> findByKnowledgeFolderIdOrderByUpdatedAtDesc(String knowledgeFolderId) {
        return documentMapper.findByKnowledgeFolderIdOrderByUpdatedAtDesc(knowledgeFolderId);
    }

    public List<KnowledgeDocument> findUnassignedOrderByUpdatedAtDesc() {
        return documentMapper.findUnassignedOrderByUpdatedAtDesc();
    }

    public List<IndexedDocument> findAllParsedDocumentsForIndexing() {
        return mapIndexedDocuments(documentMapper.findAllParsedDocumentsForIndexing(DocumentStatus.PARSED.name()));
    }

    public List<IndexedDocument> findParsedDocumentsForIndexingByKnowledgeFolderId(String knowledgeFolderId) {
        return mapIndexedDocuments(documentMapper.findParsedDocumentsForIndexingByKnowledgeFolderId(
                DocumentStatus.PARSED.name(),
                knowledgeFolderId
        ));
    }

    public List<KnowledgeChunk> findChunksByDocumentId(String documentId) {
        return documentMapper.findChunksByDocumentId(documentId);
    }

    public List<StoredChunk> findStoredChunksByIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<String> lookupIds = chunkIds.size() > MAX_STORED_CHUNK_LOOKUP_SIZE
                ? chunkIds.subList(0, MAX_STORED_CHUNK_LOOKUP_SIZE)
                : chunkIds;
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

    public void upsertDocument(KnowledgeDocument document) {
        documentMapper.upsertDocument(document);
    }

    public void updateKnowledgeFolderId(String documentId, String knowledgeFolderId, long updatedAt) {
        documentMapper.updateKnowledgeFolderId(documentId, knowledgeFolderId, updatedAt);
    }

    public List<String> findDocumentIdsByKnowledgeFolderId(String knowledgeFolderId) {
        return documentMapper.findDocumentIdsByKnowledgeFolderId(knowledgeFolderId);
    }

    public void markIndexed(String documentId, long indexedAt) {
        documentMapper.markIndexed(documentId, indexedAt);
    }

    public void clearIndexed(String documentId) {
        documentMapper.clearIndexed(documentId);
    }

    public void clearIndexedByKnowledgeFolderId(String knowledgeFolderId) {
        documentMapper.clearIndexedByKnowledgeFolderId(knowledgeFolderId);
    }

    public IndexStatistics indexStatistics() {
        return documentMapper.indexStatistics();
    }

    public void replaceChunks(String documentId, List<KnowledgeChunk> chunks) {
        documentMapper.deleteChunksByDocumentId(documentId);

        for (int start = 0; start < chunks.size(); start += CHUNK_INSERT_BATCH_SIZE) {
            int end = Math.min(start + CHUNK_INSERT_BATCH_SIZE, chunks.size());
            for (KnowledgeChunk chunk : chunks.subList(start, end)) {
                documentMapper.insertChunk(chunk);
            }
        }
    }

    public boolean deleteById(String id) {
        documentMapper.deleteChunksByDocumentId(id);
        return documentMapper.deleteDocumentById(id) > 0;
    }

    public int deleteByKnowledgeFolderId(String knowledgeFolderId) {
        documentMapper.deleteChunksByKnowledgeFolderId(knowledgeFolderId);
        return documentMapper.deleteDocumentsByKnowledgeFolderId(knowledgeFolderId);
    }

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

        private IndexedDocumentBuilder(String id, String sourcePath, String fileName, FileType fileType) {
            this.id = id;
            this.sourcePath = sourcePath;
            this.fileName = fileName;
            this.fileType = fileType;
        }

        private IndexedDocument build() {
            return new IndexedDocument(id, sourcePath, fileName, fileType, List.copyOf(chunks));
        }
    }
}
