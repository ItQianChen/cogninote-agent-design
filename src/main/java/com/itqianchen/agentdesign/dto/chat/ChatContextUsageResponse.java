package com.itqianchen.agentdesign.dto.chat;

public record ChatContextUsageResponse(
        int contextWindowTokens,
        int usedTokens,
        int availableTokens,
        double usageRatio,
        boolean compressed,
        int summaryTokens,
        int recentMessageTokens,
        int recentMessageCount,
        int totalMessageCount,
        int summaryMessageSequence,
        String estimationMethod
) {
}
