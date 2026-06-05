package com.itqianchen.agentdesign.domain.agent;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.List;
import reactor.core.publisher.Flux;

public record AgentChatStream(
        String requestId,
        String conversationId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        Flux<String> answer,
        Runnable onCancel
) {
}
