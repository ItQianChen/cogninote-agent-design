package com.itqianchen.agentdesign.domain.vo.agent;

import com.itqianchen.agentdesign.domain.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatToolEvent;
import com.itqianchen.agentdesign.domain.dto.chat.RagSourceResponse;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Supplier;

/**
 * Agent 执行后交给 SSE 层的流式响应契约。
 *
 * <p>该对象包含模型输出流、检索来源和取消回调，不是纯数据快照。流式输出期间
 * contextUsageSupplier 可能返回更新后的上下文用量，调用方读取时需要按当前状态展示。</p>
 */
public record AgentChatStream(
        String requestId,
        String conversationId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        ChatContextUsageResponse contextUsage,
        Supplier<ChatContextUsageResponse> contextUsageSupplier,
        Flux<ChatToolEvent> toolEvents,
        Flux<String> answer,
        Flux<String> reasoning,
        Runnable onCancel
) {
    /**
     * 兼容无工具事件的旧构造路径。
     */
    public AgentChatStream(
            String requestId,
            String conversationId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources,
            ChatContextUsageResponse contextUsage,
            Supplier<ChatContextUsageResponse> contextUsageSupplier,
            Flux<String> answer,
            Runnable onCancel
    ) {
        this(requestId, conversationId, retrievalMode, sources, contextUsage, contextUsageSupplier,
                Flux.empty(), answer, Flux.empty(), onCancel);
    }

    public AgentChatStream(
            String requestId, String conversationId, SearchMode retrievalMode, List<RagSourceResponse> sources,
            ChatContextUsageResponse contextUsage, Supplier<ChatContextUsageResponse> contextUsageSupplier,
            Flux<ChatToolEvent> toolEvents, Flux<String> answer, Runnable onCancel
    ) {
        this(requestId, conversationId, retrievalMode, sources, contextUsage, contextUsageSupplier,
                toolEvents, answer, Flux.empty(), onCancel);
    }

    /**
     * 返回当前可展示的上下文用量。
     *
     * <p>如果流式流程提供了延迟 supplier，以 supplier 为准，避免 meta 事件和最终状态使用不同估算口径。</p>
     */
    public ChatContextUsageResponse currentContextUsage() {
        return contextUsageSupplier == null ? contextUsage : contextUsageSupplier.get();
    }
}
