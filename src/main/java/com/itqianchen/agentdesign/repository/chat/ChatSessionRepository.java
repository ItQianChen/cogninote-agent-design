package com.itqianchen.agentdesign.repository.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ChatSessionRepository {

    private final JdbcTemplate jdbcTemplate;

    public ChatSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ChatSession> findActiveSessions() {
        return jdbcTemplate.query("""
                        SELECT *
                        FROM chat_sessions
                        WHERE deleted = 0
                        ORDER BY updated_at DESC
                        """,
                (rs, rowNum) -> mapSession(rs)
        );
    }

    public Optional<ChatSession> findById(String id) {
        List<ChatSession> sessions = jdbcTemplate.query("""
                        SELECT *
                        FROM chat_sessions
                        WHERE id = ? AND deleted = 0
                        """,
                (rs, rowNum) -> mapSession(rs),
                id
        );
        return sessions.stream().findFirst();
    }

    public ChatSession create(String title, boolean useKnowledgeBase, SearchMode mode, int topK, long now) {
        return create(UUID.randomUUID().toString(), title, useKnowledgeBase, mode, topK, now);
    }

    public ChatSession create(
            String id,
            String title,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long now
    ) {
        ChatSession session = new ChatSession(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                title == null || title.isBlank() ? "新对话" : title.trim(),
                null,
                0,
                useKnowledgeBase,
                mode == null ? SearchMode.HYBRID : mode,
                normalizeTopK(topK),
                false,
                now,
                now
        );
        jdbcTemplate.update("""
                        INSERT INTO chat_sessions (
                            id, title, summary, summary_message_sequence, use_knowledge_base,
                            retrieval_mode, top_k, deleted, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                        """,
                session.id(),
                session.title(),
                session.summary(),
                session.summaryMessageSequence(),
                session.useKnowledgeBase() ? 1 : 0,
                session.retrievalMode().name(),
                session.topK(),
                session.createdAt(),
                session.updatedAt()
        );
        return session;
    }

    public ChatSession ensureSession(
            String conversationId,
            String fallbackTitle,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long now
    ) {
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<ChatSession> existing = findById(conversationId);
            if (existing.isPresent()) {
                updateOptions(conversationId, null, useKnowledgeBase, mode, topK, now);
                return findById(conversationId).orElse(existing.get());
            }
            /*
             * SSE meta 会把 conversationId 提前返回给前端。首次发送时必须用同一个
             * id 创建 SQLite 会话，否则前端持有的会话 id 与落库消息会分叉。
             */
            return create(conversationId, fallbackTitle, useKnowledgeBase, mode, topK, now);
        }
        return create(fallbackTitle, useKnowledgeBase, mode, topK, now);
    }

    public void updateOptions(
            String id,
            String title,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long updatedAt
    ) {
        jdbcTemplate.update("""
                        UPDATE chat_sessions
                        SET title = COALESCE(?, title),
                            use_knowledge_base = ?,
                            retrieval_mode = ?,
                            top_k = ?,
                            updated_at = ?
                        WHERE id = ? AND deleted = 0
                        """,
                title == null || title.isBlank() ? null : title.trim(),
                useKnowledgeBase ? 1 : 0,
                (mode == null ? SearchMode.HYBRID : mode).name(),
                normalizeTopK(topK),
                updatedAt,
                id
        );
    }

    public void updateSummary(String id, String summary, int coveredSequence, long updatedAt) {
        jdbcTemplate.update("""
                        UPDATE chat_sessions
                        SET summary = ?, summary_message_sequence = ?, updated_at = ?
                        WHERE id = ? AND deleted = 0
                        """,
                summary,
                coveredSequence,
                updatedAt,
                id
        );
    }

    public boolean softDelete(String id, long updatedAt) {
        return jdbcTemplate.update("""
                        UPDATE chat_sessions
                        SET deleted = 1, updated_at = ?
                        WHERE id = ? AND deleted = 0
                        """,
                updatedAt,
                id
        ) > 0;
    }

    public void clearMessages(String conversationId, long updatedAt) {
        jdbcTemplate.update("DELETE FROM chat_messages WHERE conversation_id = ?", conversationId);
        jdbcTemplate.update("""
                        UPDATE chat_sessions
                        SET summary = NULL, summary_message_sequence = 0, updated_at = ?
                        WHERE id = ? AND deleted = 0
                        """,
                updatedAt,
                conversationId
        );
    }

    public List<ChatMessage> findMessages(String conversationId) {
        return jdbcTemplate.query("""
                        SELECT *
                        FROM chat_messages
                        WHERE conversation_id = ?
                        ORDER BY message_sequence ASC
                        """,
                (rs, rowNum) -> mapMessage(rs),
                conversationId
        );
    }

    public int countMessages(String conversationId) {
        Integer count = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM chat_messages
                        WHERE conversation_id = ?
                        """,
                Integer.class,
                conversationId
        );
        return count == null ? 0 : count;
    }

    public List<ChatMessage> findMessagesAfter(String conversationId, int sequence) {
        return jdbcTemplate.query("""
                        SELECT *
                        FROM chat_messages
                        WHERE conversation_id = ? AND message_sequence > ?
                        ORDER BY message_sequence ASC
                        """,
                (rs, rowNum) -> mapMessage(rs),
                conversationId,
                sequence
        );
    }

    public ChatMessage appendMessage(
            String conversationId,
            ChatMessageRole role,
            String content,
            ChatMessageStatus status,
            String requestId,
            SearchMode retrievalMode,
            String sourcesJson,
            int tokenEstimate,
            long createdAt
    ) {
        Integer nextSequence = jdbcTemplate.queryForObject("""
                        SELECT COALESCE(MAX(message_sequence), 0) + 1
                        FROM chat_messages
                        WHERE conversation_id = ?
                        """,
                Integer.class,
                conversationId
        );
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                conversationId,
                nextSequence == null ? 1 : nextSequence,
                role,
                content,
                status,
                requestId,
                retrievalMode,
                sourcesJson,
                tokenEstimate,
                createdAt
        );
        jdbcTemplate.update("""
                        INSERT INTO chat_messages (
                            id, conversation_id, message_sequence, role, content, status,
                            request_id, retrieval_mode, sources_json, token_estimate, created_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                message.id(),
                message.conversationId(),
                message.sequence(),
                message.role().name(),
                message.content(),
                message.status().name(),
                message.requestId(),
                message.retrievalMode() == null ? null : message.retrievalMode().name(),
                message.sourcesJson(),
                message.tokenEstimate(),
                message.createdAt()
        );
        jdbcTemplate.update("UPDATE chat_sessions SET updated_at = ? WHERE id = ?", createdAt, conversationId);
        return message;
    }

    private static ChatSession mapSession(ResultSet rs) throws SQLException {
        return new ChatSession(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("summary"),
                rs.getInt("summary_message_sequence"),
                rs.getInt("use_knowledge_base") == 1,
                SearchMode.valueOf(rs.getString("retrieval_mode")),
                rs.getInt("top_k"),
                rs.getInt("deleted") == 1,
                rs.getLong("created_at"),
                rs.getLong("updated_at")
        );
    }

    private static ChatMessage mapMessage(ResultSet rs) throws SQLException {
        String retrievalMode = rs.getString("retrieval_mode");
        return new ChatMessage(
                rs.getString("id"),
                rs.getString("conversation_id"),
                rs.getInt("message_sequence"),
                ChatMessageRole.valueOf(rs.getString("role")),
                rs.getString("content"),
                ChatMessageStatus.valueOf(rs.getString("status")),
                rs.getString("request_id"),
                retrievalMode == null ? null : SearchMode.valueOf(retrievalMode),
                rs.getString("sources_json"),
                rs.getInt("token_estimate"),
                rs.getLong("created_at")
        );
    }

    private static int normalizeTopK(int topK) {
        return Math.clamp(topK, 1, 50);
    }
}
