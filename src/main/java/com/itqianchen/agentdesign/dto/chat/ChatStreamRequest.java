package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatStreamRequest(
        @NotBlank @Size(max = 4000) String question,
        Integer topK,
        SearchMode mode,
        @Size(max = 80) String requestId,
        @Size(max = 80) String conversationId,
        Boolean useKnowledgeBase
) {
}


