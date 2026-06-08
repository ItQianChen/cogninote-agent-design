package com.itqianchen.agentdesign.service.chat;

import java.util.List;

public record ConversationMemorySnapshot(
        String summary,
        List<ConversationMemoryEntry> recentMessages,
        int lastIncludedSequence,
        int summaryTokens,
        int recentMessageTokens,
        int totalMessageCount,
        int contextWindowTokens,
        int historyBudgetTokens,
        String estimationMethod
) {
}
