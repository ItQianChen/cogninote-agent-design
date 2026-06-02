package com.itqianchen.agentdesign.document;

import com.itqianchen.agentdesign.search.IndexStatistics;
import com.itqianchen.agentdesign.search.IndexedChunk;
import com.itqianchen.agentdesign.search.IndexedDocument;
import com.itqianchen.agentdesign.search.StoredChunk;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {

    private static final int CHUNK_INSERT_BATCH_SIZE = 200;
    private static final int MAX_STORED_CHUNK_LOOKUP_SIZE = 500;

    private final JdbcTemplate jdbcTemplate;

    public DocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<KnowledgeDocument> findById(String id) {
        List<KnowledgeDocument> documents = jdbcTemplate.query("""
                        SELECT d.*, COUNT(c.id) AS chunk_count
                        FROM documents d
                        LEFT JOIN chunks c ON c.document_id = d.id
                        WHERE d.id = ?
                        GROUP BY d.id
                        """,
                (rs, rowNum) -> mapDocument(rs),
                id
        );
        return documents.stream().findFirst();
    }

    public List<KnowledgeDocument> findAllOrderByUpdatedAtDesc() {
        return jdbcTemplate.query("""
                        SELECT d.*, COUNT(c.id) AS chunk_count
                        FROM documents d
                        LEFT JOIN chunks c ON c.document_id = d.id
                        GROUP BY d.id
                        ORDER BY d.updated_at DESC
                        """,
                (rs, rowNum) -> mapDocument(rs)
        );
    }

    public Optional<IndexedDocument> findParsedDocumentForIndexing(String documentId) {
        return findById(documentId)
                .filter(document -> document.status() == DocumentStatus.PARSED)
                .map(document -> new IndexedDocument(
                        document.id(),
                        document.sourcePath(),
                        document.fileName(),
                        document.fileType(),
                        findChunksByDocumentId(document.id()).stream()
                                .map(chunk -> new IndexedChunk(
                                        chunk.id(),
                                        chunk.documentId(),
                                        chunk.chunkIndex(),
                                        chunk.content(),
                                        chunk.contentHash(),
                                        chunk.pageNumber(),
                                        chunk.heading()
                                ))
                                .toList()
                ));
    }

    public List<IndexedDocument> findAllParsedDocumentsForIndexing() {
        return jdbcTemplate.query("""
                        SELECT
                            d.id AS document_id,
                            d.source_path,
                            d.file_name,
                            d.file_type,
                            c.id AS chunk_id,
                            c.chunk_index,
                            c.content,
                            c.content_hash,
                            c.page_number,
                            c.heading
                        FROM documents d
                        JOIN chunks c ON c.document_id = d.id
                        WHERE d.status = ?
                        ORDER BY d.updated_at DESC, c.chunk_index ASC
                        """,
                rs -> {
                    Map<String, IndexedDocumentBuilder> documents = new LinkedHashMap<>();
                    while (rs.next()) {
                        String documentId = rs.getString("document_id");
                        IndexedDocumentBuilder builder = documents.computeIfAbsent(
                                documentId,
                                ignored -> new IndexedDocumentBuilder(
                                        documentId,
                                        getString(rs, "source_path"),
                                        getString(rs, "file_name"),
                                        FileType.valueOf(getString(rs, "file_type"))
                                )
                        );
                        builder.chunks.add(new IndexedChunk(
                                rs.getString("chunk_id"),
                                documentId,
                                rs.getInt("chunk_index"),
                                rs.getString("content"),
                                rs.getString("content_hash"),
                                getNullableInt(rs, "page_number"),
                                rs.getString("heading")
                        ));
                    }

                    return documents.values().stream()
                            .map(IndexedDocumentBuilder::build)
                            .toList();
                },
                DocumentStatus.PARSED.name()
        );
    }

    public List<KnowledgeChunk> findChunksByDocumentId(String documentId) {
        return jdbcTemplate.query("""
                        SELECT id, document_id, chunk_index, content, content_hash,
                               page_number, heading, token_count, created_at
                        FROM chunks
                        WHERE document_id = ?
                        ORDER BY chunk_index ASC
                        """,
                (rs, rowNum) -> mapChunk(rs),
                documentId
        );
    }

    public List<StoredChunk> findStoredChunksByIds(List<String> chunkIds) {
        if (chunkIds.isEmpty()) {
            return List.of();
        }

        List<String> lookupIds = chunkIds.size() > MAX_STORED_CHUNK_LOOKUP_SIZE
                ? chunkIds.subList(0, MAX_STORED_CHUNK_LOOKUP_SIZE)
                : chunkIds;
        String placeholders = String.join(",", lookupIds.stream().map(id -> "?").toList());
        List<StoredChunk> chunks = jdbcTemplate.query("""
                        SELECT
                            c.id AS chunk_id,
                            c.document_id,
                            c.chunk_index,
                            c.content,
                            c.content_hash,
                            c.page_number,
                            c.heading,
                            d.file_name,
                            d.source_path
                        FROM chunks c
                        JOIN documents d ON d.id = c.document_id
                        WHERE c.id IN (%s)
                        """.formatted(placeholders),
                (rs, rowNum) -> new StoredChunk(
                        rs.getString("chunk_id"),
                        rs.getString("document_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getString("content_hash"),
                        getNullableInt(rs, "page_number"),
                        rs.getString("heading"),
                        rs.getString("file_name"),
                        rs.getString("source_path")
                ),
                lookupIds.toArray()
        );

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
        jdbcTemplate.update("""
                        INSERT INTO documents (
                            id, source_path, file_name, file_type, file_size, last_modified,
                            content_hash, status, indexed_at, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT(id) DO UPDATE SET
                            source_path = excluded.source_path,
                            file_name = excluded.file_name,
                            file_type = excluded.file_type,
                            file_size = excluded.file_size,
                            last_modified = excluded.last_modified,
                            content_hash = excluded.content_hash,
                            status = excluded.status,
                            indexed_at = excluded.indexed_at,
                            updated_at = excluded.updated_at
                        """,
                document.id(),
                document.sourcePath(),
                document.fileName(),
                document.fileType().name(),
                document.fileSize(),
                document.lastModified(),
                document.contentHash(),
                document.status().name(),
                document.indexedAt(),
                document.createdAt(),
                document.updatedAt()
        );
    }

    public void markIndexed(String documentId, long indexedAt) {
        jdbcTemplate.update("UPDATE documents SET indexed_at = ? WHERE id = ?", indexedAt, documentId);
    }

    public void clearIndexed(String documentId) {
        jdbcTemplate.update("UPDATE documents SET indexed_at = NULL WHERE id = ?", documentId);
    }

    public IndexStatistics indexStatistics() {
        return jdbcTemplate.queryForObject("""
                        SELECT
                            SUM(CASE WHEN status = 'PARSED' THEN 1 ELSE 0 END) AS parsed_document_count,
                            SUM(CASE WHEN status = 'PARSED' AND indexed_at IS NULL THEN 1 ELSE 0 END) AS unindexed_document_count,
                            MAX(indexed_at) AS last_indexed_at
                        FROM documents
                        """,
                (rs, rowNum) -> new IndexStatistics(
                        rs.getLong("parsed_document_count"),
                        rs.getLong("unindexed_document_count"),
                        getNullableLong(rs, "last_indexed_at")
                )
        );
    }

    public void replaceChunks(String documentId, List<KnowledgeChunk> chunks) {
        jdbcTemplate.update("DELETE FROM chunks WHERE document_id = ?", documentId);

        if (chunks.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate("""
                        INSERT INTO chunks (
                            id, document_id, chunk_index, content, content_hash,
                            page_number, heading, token_count, created_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                chunks,
                CHUNK_INSERT_BATCH_SIZE,
                (preparedStatement, chunk) -> {
                    preparedStatement.setString(1, chunk.id());
                    preparedStatement.setString(2, chunk.documentId());
                    preparedStatement.setInt(3, chunk.chunkIndex());
                    preparedStatement.setString(4, chunk.content());
                    preparedStatement.setString(5, chunk.contentHash());
                    preparedStatement.setObject(6, chunk.pageNumber());
                    preparedStatement.setString(7, chunk.heading());
                    preparedStatement.setInt(8, chunk.tokenCount());
                    preparedStatement.setLong(9, chunk.createdAt());
                }
        );
    }

    public boolean deleteById(String id) {
        jdbcTemplate.update("DELETE FROM chunks WHERE document_id = ?", id);
        return jdbcTemplate.update("DELETE FROM documents WHERE id = ?", id) > 0;
    }

    private KnowledgeDocument mapDocument(ResultSet rs) throws SQLException {
        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("source_path"),
                rs.getString("file_name"),
                FileType.valueOf(rs.getString("file_type")),
                rs.getLong("file_size"),
                rs.getLong("last_modified"),
                rs.getString("content_hash"),
                DocumentStatus.valueOf(rs.getString("status")),
                getNullableLong(rs, "indexed_at"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("chunk_count")
        );
    }

    private KnowledgeChunk mapChunk(ResultSet rs) throws SQLException {
        return new KnowledgeChunk(
                rs.getString("id"),
                rs.getString("document_id"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getString("content_hash"),
                getNullableInt(rs, "page_number"),
                rs.getString("heading"),
                rs.getInt("token_count"),
                rs.getLong("created_at")
        );
    }

    private static Integer getNullableInt(ResultSet rs, String columnName) throws SQLException {
        int value = rs.getInt(columnName);
        return rs.wasNull() ? null : value;
    }

    private static Long getNullableLong(ResultSet rs, String columnName) throws SQLException {
        long value = rs.getLong(columnName);
        return rs.wasNull() ? null : value;
    }

    private static String getString(ResultSet rs, String columnName) {
        try {
            return rs.getString(columnName);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to read column: " + columnName, ex);
        }
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
