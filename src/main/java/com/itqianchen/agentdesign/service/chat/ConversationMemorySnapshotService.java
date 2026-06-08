package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemorySnapshotService {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatMemoryProperties memoryProperties;
    private final TokenEstimator tokenEstimator;
    private final ModelConfigService modelConfigService;

    public ConversationMemorySnapshotService(
            ChatSessionRepository chatSessionRepository,
            ChatMemoryProperties memoryProperties,
            TokenEstimator tokenEstimator,
            ModelConfigService modelConfigService
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.memoryProperties = memoryProperties;
        this.tokenEstimator = tokenEstimator;
        this.modelConfigService = modelConfigService;
    }

    public ConversationMemorySnapshot snapshot(String conversationId) {
        return snapshot(conversationId, Integer.MAX_VALUE);
    }

    public ConversationMemorySnapshot snapshot(String conversationId, int maxSequenceInclusive) {
        ModelConfig chatConfig = modelConfigService.activeChatOrDefault();
        ChatSession session = chatSessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return emptySnapshot(chatConfig);
        }

        List<ChatMessage> messages = chatSessionRepository.findMessagesAfter(
                conversationId,
                session.summaryMessageSequence()
        ).stream()
                .filter(message -> message.sequence() <= maxSequenceInclusive)
                .toList();
        List<ChatMessage> selected = selectByBudget(messages, chatConfig);
        int lastSequence = selected.isEmpty() ? session.summaryMessageSequence() : selected.getLast().sequence();
        int summaryTokens = estimateSummaryTokens(session.summary(), chatConfig);
        int recentMessageTokens = estimateMessageTokens(selected, chatConfig);
        return new ConversationMemorySnapshot(
                session.summary(),
                selected.stream().map(ConversationMemorySnapshotService::toMemoryEntry).toList(),
                lastSequence,
                summaryTokens,
                recentMessageTokens,
                chatSessionRepository.countMessages(conversationId),
                chatConfig.resolvedContextWindowTokens(),
                historyBudgetTokens(chatConfig),
                estimationMethod(session.summary(), selected, chatConfig)
        );
    }

    private List<ChatMessage> selectByBudget(List<ChatMessage> messages, ModelConfig chatConfig) {
        if (messages.isEmpty()) {
            return List.of();
        }

        int minimum = memoryProperties.resolvedMinimumRecentMessages();
        int budget = historyBudgetTokens(chatConfig);
        List<ChatMessage> selected = new ArrayList<>();
        int tokens = 0;

        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            int nextTokens = tokens + estimateMessageTokens(message, chatConfig);
            boolean insideMinimumWindow = selected.size() < minimum;
            if (!insideMinimumWindow && nextTokens > budget) {
                break;
            }
            selected.addFirst(message);
            tokens = nextTokens;
        }
        return List.copyOf(selected);
    }

    public int historyBudgetTokens(ModelConfig chatConfig) {
        int contextWindowTokens = chatConfig == null ? 0 : chatConfig.resolvedContextWindowTokens();
        if (contextWindowTokens <= 0) {
            return memoryProperties.resolvedMaxHistoryTokens();
        }
        return Math.max(256, (int) Math.floor(contextWindowTokens * 0.8));
    }

    public int estimateMessageTokens(List<ChatMessage> messages, ModelConfig chatConfig) {
        int tokens = 0;
        for (ChatMessage message : messages) {
            tokens += estimateMessageTokens(message, chatConfig);
        }
        return tokens;
    }

    public int estimateMessageTokens(ChatMessage message, ModelConfig chatConfig) {
        return Math.max(1, tokenEstimator.estimateChatMessage(message.content(), chatConfig));
    }

    public int estimateSummaryTokens(String summary, ModelConfig chatConfig) {
        return summary == null || summary.isBlank() ? 0 : tokenEstimator.estimateChatMessage(summary, chatConfig);
    }

    private ConversationMemorySnapshot emptySnapshot(ModelConfig chatConfig) {
        return new ConversationMemorySnapshot(
                null,
                List.of(),
                0,
                0,
                0,
                0,
                chatConfig.resolvedContextWindowTokens(),
                historyBudgetTokens(chatConfig),
                tokenEstimator.estimateWithMethod("context", chatConfig).method()
        );
    }

    private String estimationMethod(String summary, List<ChatMessage> selected, ModelConfig chatConfig) {
        if (summary != null && !summary.isBlank()) {
            return tokenEstimator.estimateWithMethod(summary, chatConfig).method();
        }
        for (ChatMessage message : selected) {
            if (message.content() != null && !message.content().isBlank()) {
                return tokenEstimator.estimateWithMethod(message.content(), chatConfig).method();
            }
        }
        return tokenEstimator.estimateWithMethod("context", chatConfig).method();
    }

    private static ConversationMemoryEntry toMemoryEntry(ChatMessage message) {
        return new ConversationMemoryEntry(
                message.agentType(),
                message.role(),
                message.content(),
                message.retrievalMode()
        );
    }
}
