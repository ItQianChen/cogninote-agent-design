package com.itqianchen.agentdesign.domain.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.chat.memory")
public record ChatMemoryProperties(
        int maxHistoryTokens,
        int minimumRecentMessages,
        int summarizeAfterMessages
) {

    private static final int DEFAULT_MAX_HISTORY_TOKENS = 6000;
    private static final int DEFAULT_MINIMUM_RECENT_MESSAGES = 8;
    private static final int DEFAULT_SUMMARIZE_AFTER_MESSAGES = 40;

    public int resolvedMaxHistoryTokens() {
        return maxHistoryTokens > 0 ? maxHistoryTokens : DEFAULT_MAX_HISTORY_TOKENS;
    }

    public int resolvedMinimumRecentMessages() {
        return Math.max(2, minimumRecentMessages > 0 ? minimumRecentMessages : DEFAULT_MINIMUM_RECENT_MESSAGES);
    }

    public int resolvedSummarizeAfterMessages() {
        return Math.max(
                resolvedMinimumRecentMessages() + 2,
                summarizeAfterMessages > 0 ? summarizeAfterMessages : DEFAULT_SUMMARIZE_AFTER_MESSAGES
        );
    }
}
