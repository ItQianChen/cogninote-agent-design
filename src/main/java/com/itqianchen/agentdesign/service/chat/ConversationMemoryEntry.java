package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.search.SearchMode;

public record ConversationMemoryEntry(
        AgentType agentType,
        ChatMessageRole role,
        String content,
        SearchMode retrievalMode
) {
}
