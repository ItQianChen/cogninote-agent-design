package com.itqianchen.agentdesign.mapper.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ChatSessionMapper {

    List<ChatSession> findActiveSessions();

    List<ChatSessionSummaryRow> findActiveSessionSummaries();

    List<ChatSession> findById(@Param("id") String id);

    int insertSession(ChatSession session);

    void updateOptions(
            @Param("id") String id,
            @Param("title") String title,
            @Param("useKnowledgeBase") boolean useKnowledgeBase,
            @Param("retrievalMode") String retrievalMode,
            @Param("topK") int topK,
            @Param("updatedAt") long updatedAt
    );

    void updateSummary(
            @Param("id") String id,
            @Param("summary") String summary,
            @Param("coveredSequence") int coveredSequence,
            @Param("updatedAt") long updatedAt
    );

    int softDelete(@Param("id") String id, @Param("updatedAt") long updatedAt);

    void deleteMessages(@Param("conversationId") String conversationId);

    void resetSessionMessages(@Param("conversationId") String conversationId, @Param("updatedAt") long updatedAt);

    List<ChatMessage> findMessages(@Param("conversationId") String conversationId);

    int countMessages(@Param("conversationId") String conversationId);

    List<ChatMessage> findMessagesAfter(
            @Param("conversationId") String conversationId,
            @Param("sequence") int sequence
    );

    int nextMessageSequence(@Param("conversationId") String conversationId);

    void insertMessage(ChatMessage message);

    void touchSession(@Param("conversationId") String conversationId, @Param("updatedAt") long updatedAt);
}
