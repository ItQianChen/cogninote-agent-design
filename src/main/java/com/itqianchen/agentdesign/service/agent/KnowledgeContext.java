package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.List;

public record KnowledgeContext(
        SearchMode retrievalMode,
        List<RagSourceResponse> sources
) {
}
