package com.itqianchen.agentdesign.domain.interfaces.agent;

import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatToolEvent;
import com.itqianchen.agentdesign.domain.dto.chat.RagSourceResponse;
import java.util.List;

/**
 * 智能体 事件 描述 智能体编排 的事件载荷。
 * <p>主要用于 SSE 流、内部路由或状态传递场景。</p>
 */
public sealed interface AgentEvent permits AgentEvent.Meta, AgentEvent.Tool, AgentEvent.Delta, AgentEvent.Done, AgentEvent.Error {

    record Meta(
            String requestId,
            String conversationId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources,
            ChatContextUsageResponse contextUsage
    ) implements AgentEvent {
    }

    record Delta(String text, String reasoning) implements AgentEvent {
    }

    record Tool(ChatToolEvent event) implements AgentEvent {
    }

    record Done(Object usage, ChatContextUsageResponse contextUsage) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }
}
