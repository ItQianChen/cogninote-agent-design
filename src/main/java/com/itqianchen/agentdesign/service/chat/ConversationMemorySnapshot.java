package com.itqianchen.agentdesign.service.chat;

import java.util.List;
import org.springframework.ai.chat.messages.Message;

public record ConversationMemorySnapshot(
        String summary,
        List<Message> recentMessages,
        int lastIncludedSequence
) {
}
