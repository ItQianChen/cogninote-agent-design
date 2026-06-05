package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemorySnapshotService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMemoryProperties memoryProperties;

    public ConversationMemorySnapshotService(
            ChatSessionRepository chatSessionRepository,
            ChatMemoryProperties memoryProperties
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.memoryProperties = memoryProperties;
    }

    public ConversationMemorySnapshot snapshot(String conversationId) {
        return snapshot(conversationId, Integer.MAX_VALUE);
    }

    public ConversationMemorySnapshot snapshot(String conversationId, int maxSequenceInclusive) {
        ChatSession session = chatSessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return new ConversationMemorySnapshot(null, List.of(), 0);
        }

        List<ChatMessage> messages = chatSessionRepository.findMessagesAfter(
                conversationId,
                session.summaryMessageSequence()
        ).stream()
                .filter(message -> message.sequence() <= maxSequenceInclusive)
                .toList();
        List<ChatMessage> selected = selectByBudget(messages);
        int lastSequence = selected.isEmpty() ? session.summaryMessageSequence() : selected.getLast().sequence();
        return new ConversationMemorySnapshot(
                session.summary(),
                selected.stream().map(ConversationMemorySnapshotService::toSpringMessage).toList(),
                lastSequence
        );
    }

    private List<ChatMessage> selectByBudget(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return List.of();
        }

        int minimum = memoryProperties.resolvedMinimumRecentMessages();
        int budget = memoryProperties.resolvedMaxHistoryTokens();
        List<ChatMessage> selected = new ArrayList<>();
        int tokens = 0;

        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            int nextTokens = tokens + Math.max(1, message.tokenEstimate());
            boolean insideMinimumWindow = selected.size() < minimum;
            if (!insideMinimumWindow && nextTokens > budget) {
                break;
            }
            selected.addFirst(message);
            tokens = nextTokens;
        }
        return List.copyOf(selected);
    }

    private static Message toSpringMessage(ChatMessage message) {
        if (message.role() == ChatMessageRole.ASSISTANT) {
            return new AssistantMessage(message.content());
        }
        return new UserMessage(message.content());
    }
}
