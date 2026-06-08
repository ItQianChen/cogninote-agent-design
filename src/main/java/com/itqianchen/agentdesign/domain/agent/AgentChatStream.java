package com.itqianchen.agentdesign.domain.agent;

import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import java.util.List;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

public record AgentChatStream(
        String requestId,
        String conversationId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        ChatContextUsageResponse contextUsage,
        Supplier<ChatContextUsageResponse> contextUsageSupplier,
        Flux<String> answer,
        Runnable onCancel
) {
    public ChatContextUsageResponse currentContextUsage() {
        return contextUsageSupplier == null ? contextUsage : contextUsageSupplier.get();
    }
}
