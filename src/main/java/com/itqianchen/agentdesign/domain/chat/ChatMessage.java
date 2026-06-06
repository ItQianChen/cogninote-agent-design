package com.itqianchen.agentdesign.domain.chat;

import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.search.SearchMode;

public record ChatMessage(
        String id,
        String conversationId,
        int sequence,
        ChatMessageRole role,
        String content,
        ChatMessageStatus status,
        String requestId,
        AgentType agentType,
        SearchMode retrievalMode,
        String sourcesJson,
        int tokenEstimate,
        long createdAt
) {
}
