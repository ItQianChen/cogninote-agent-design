package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import java.util.List;

public record ChatMetaEvent(
        String requestId,
        String conversationId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        ChatContextUsageResponse contextUsage
) {
    public ChatMetaEvent(
            String requestId,
            String conversationId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources,
            ChatContextUsageResponse contextUsage
    ) {
        this.requestId = requestId;
        this.conversationId = conversationId;
        this.retrievalMode = retrievalMode;
        this.sources = sources.stream()
                .map(source -> source.withContent(null))
                .toList();
        this.contextUsage = contextUsage;
    }
}


