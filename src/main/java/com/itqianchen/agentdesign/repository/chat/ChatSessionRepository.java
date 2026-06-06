package com.itqianchen.agentdesign.repository.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatSessionResponse;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionMapper;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionSummaryRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class ChatSessionRepository {

    private final ChatSessionMapper chatSessionMapper;

    public ChatSessionRepository(ChatSessionMapper chatSessionMapper) {
        this.chatSessionMapper = chatSessionMapper;
    }

    public List<ChatSession> findActiveSessions() {
        return chatSessionMapper.findActiveSessions();
    }

    public List<ChatSessionResponse> findActiveSessionSummaries() {
        return chatSessionMapper.findActiveSessionSummaries().stream()
                .map(ChatSessionRepository::toSummaryResponse)
                .toList();
    }

    public Optional<ChatSession> findById(String id) {
        return chatSessionMapper.findById(id).stream().findFirst();
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
        if (chatSessionMapper.insertSession(session) > 0) {
            return session;
        }
        return findById(session.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Chat session id already exists but is not active: " + session.id()
                ));
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
            ChatSession session = create(conversationId, fallbackTitle, useKnowledgeBase, mode, topK, now);
            updateOptions(conversationId, null, useKnowledgeBase, mode, topK, now);
            return findById(conversationId).orElse(session);
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
        chatSessionMapper.updateOptions(
                id,
                title == null || title.isBlank() ? null : title.trim(),
                useKnowledgeBase,
                (mode == null ? SearchMode.HYBRID : mode).name(),
                normalizeTopK(topK),
                updatedAt
        );
    }

    public void updateSummary(String id, String summary, int coveredSequence, long updatedAt) {
        chatSessionMapper.updateSummary(id, summary, coveredSequence, updatedAt);
    }

    public boolean softDelete(String id, long updatedAt) {
        return chatSessionMapper.softDelete(id, updatedAt) > 0;
    }

    public void clearMessages(String conversationId, long updatedAt) {
        chatSessionMapper.deleteMessages(conversationId);
        chatSessionMapper.resetSessionMessages(conversationId, updatedAt);
    }

    public List<ChatMessage> findMessages(String conversationId) {
        return chatSessionMapper.findMessages(conversationId);
    }

    public int countMessages(String conversationId) {
        return chatSessionMapper.countMessages(conversationId);
    }

    public List<ChatMessage> findMessagesAfter(String conversationId, int sequence) {
        return chatSessionMapper.findMessagesAfter(conversationId, sequence);
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
        int nextSequence = chatSessionMapper.nextMessageSequence(conversationId);
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                conversationId,
                nextSequence,
                role,
                content,
                status,
                requestId,
                retrievalMode,
                sourcesJson,
                tokenEstimate,
                createdAt
        );
        chatSessionMapper.insertMessage(message);
        chatSessionMapper.touchSession(conversationId, createdAt);
        return message;
    }

    private static ChatSessionResponse toSummaryResponse(ChatSessionSummaryRow row) {
        ChatSession session = new ChatSession(
                row.id(),
                row.title(),
                row.summary(),
                row.summaryMessageSequence(),
                row.useKnowledgeBase(),
                row.retrievalMode(),
                row.topK(),
                row.deleted(),
                row.createdAt(),
                row.updatedAt()
        );
        return ChatSessionResponse.summary(session, row.messageCount());
    }

    private static int normalizeTopK(int topK) {
        return Math.clamp(topK, 1, 50);
    }
}
