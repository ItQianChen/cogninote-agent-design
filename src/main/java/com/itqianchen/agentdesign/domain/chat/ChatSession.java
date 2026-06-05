package com.itqianchen.agentdesign.domain.chat;

import com.itqianchen.agentdesign.domain.search.SearchMode;

public record ChatSession(
        String id,
        String title,
        String summary,
        int summaryMessageSequence,
        boolean useKnowledgeBase,
        SearchMode retrievalMode,
        int topK,
        boolean deleted,
        long createdAt,
        long updatedAt
) {
}
