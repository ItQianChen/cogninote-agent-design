package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import java.util.List;

public record ChatMessageResponse(
        String id,
        ChatMessageRole role,
        String content,
        String status,
        String requestId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        long createdAt
) {

    public static ChatMessageResponse from(ChatMessage message, List<RagSourceResponse> sources) {
        return new ChatMessageResponse(
                message.id(),
                message.role(),
                message.content(),
                message.status().name().toLowerCase(),
                message.requestId(),
                message.retrievalMode(),
                sources,
                message.createdAt()
        );
    }
}
