package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import jakarta.validation.constraints.Size;

public record ChatSessionCreateRequest(
        @Size(max = 120) String title,
        Boolean useKnowledgeBase,
        SearchMode mode,
        Integer topK
) {
}
