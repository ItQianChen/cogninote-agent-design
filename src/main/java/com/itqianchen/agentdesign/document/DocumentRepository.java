package com.itqianchen.agentdesign.document;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {

    private static final int CHUNK_INSERT_BATCH_SIZE = 200;

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
        long indexedAt = rs.getLong("indexed_at");
        Long nullableIndexedAt = rs.wasNull() ? null : indexedAt;

        return new KnowledgeDocument(
                rs.getString("id"),
                rs.getString("source_path"),
                rs.getString("file_name"),
                FileType.valueOf(rs.getString("file_type")),
                rs.getLong("file_size"),
                rs.getLong("last_modified"),
                rs.getString("content_hash"),
                DocumentStatus.valueOf(rs.getString("status")),
                nullableIndexedAt,
                rs.getLong("created_at"),
                rs.getLong("updated_at"),
                rs.getInt("chunk_count")
        );
    }
}
