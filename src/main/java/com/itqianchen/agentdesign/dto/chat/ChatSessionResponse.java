package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import java.util.List;

public record ChatSessionResponse(
        String id,
        String title,
        String summary,
        boolean useKnowledgeBase,
        SearchMode mode,
        int topK,
        long createdAt,
        long updatedAt,
        int messageCount,
        List<ChatMessageResponse> messages
) {

    public static ChatSessionResponse summary(ChatSession session) {
        return summary(session, 0);
    }

    public static ChatSessionResponse summary(ChatSession session, int messageCount) {
        return new ChatSessionResponse(
                session.id(),
                session.title(),
                session.summary(),
                session.useKnowledgeBase(),
                session.retrievalMode(),
                session.topK(),
                session.createdAt(),
                session.updatedAt(),
                messageCount,
                List.of()
        );
    }

    public static ChatSessionResponse from(ChatSession session, List<ChatMessageResponse> messages) {
        return new ChatSessionResponse(
                session.id(),
                session.title(),
                session.summary(),
                session.useKnowledgeBase(),
                session.retrievalMode(),
                session.topK(),
                session.createdAt(),
                session.updatedAt(),
                messages.size(),
                messages
        );
    }
}
