package com.itqianchen.agentdesign.domain.agent;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatStreamRequest;

public record AgentRequest(
        String requestId,
        String question,
        Integer topK,
        SearchMode mode,
        String conversationId,
        boolean useKnowledgeBase
) {

    public static AgentRequest from(ChatStreamRequest request) {
        return new AgentRequest(
                request.requestId(),
                request.question().trim(),
                request.topK(),
                request.mode(),
                request.conversationId(),
                request.useKnowledgeBase() == null || request.useKnowledgeBase()
        );
    }
}
