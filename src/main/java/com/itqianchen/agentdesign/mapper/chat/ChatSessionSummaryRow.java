package com.itqianchen.agentdesign.mapper.chat;

import com.itqianchen.agentdesign.domain.search.SearchMode;

public record ChatSessionSummaryRow(
        String id,
        String title,
        String summary,
        int summaryMessageSequence,
        boolean useKnowledgeBase,
        SearchMode retrievalMode,
        int topK,
        boolean deleted,
        long createdAt,
        long updatedAt,
        int messageCount
) {
}
