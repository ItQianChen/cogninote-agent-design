package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ChatContextUsageService {

    private final ChatSessionRepository chatSessionRepository;
    private final ConversationMemorySnapshotService memorySnapshotService;
    private final ModelConfigService modelConfigService;
    private final ChatMemoryProperties memoryProperties;

    public ChatContextUsageService(
            ChatSessionRepository chatSessionRepository,
            ConversationMemorySnapshotService memorySnapshotService,
            ModelConfigService modelConfigService,
            ChatMemoryProperties memoryProperties
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.memorySnapshotService = memorySnapshotService;
        this.modelConfigService = modelConfigService;
        this.memoryProperties = memoryProperties;
    }

    public ChatContextUsageResponse usage(String conversationId) {
        ChatSession session = chatSessionRepository.findById(conversationId).orElse(null);
        ModelConfig chatConfig = modelConfigService.activeChatOrDefault();
        if (session == null) {
            return emptyUsage(chatConfig);
        }
        ConversationMemorySnapshot snapshot = memorySnapshotService.snapshot(conversationId);
        return fromSnapshot(session, snapshot);
    }

    public ChatContextUsageResponse fromSnapshot(ChatSession session, ConversationMemorySnapshot snapshot) {
        int contextWindowTokens = Math.max(1, snapshot.contextWindowTokens());
        int usedTokens = Math.max(0, snapshot.summaryTokens() + snapshot.recentMessageTokens());
        int availableTokens = Math.max(0, contextWindowTokens - usedTokens);
        return new ChatContextUsageResponse(
                contextWindowTokens,
                usedTokens,
                availableTokens,
                Math.min(1.0, usedTokens / (double) contextWindowTokens),
                session.summaryMessageSequence() > 0 && session.summary() != null && !session.summary().isBlank(),
                snapshot.summaryTokens(),
                snapshot.recentMessageTokens(),
                snapshot.recentMessages().size(),
                snapshot.totalMessageCount(),
                session.summaryMessageSequence(),
                snapshot.estimationMethod()
        );
    }

    public boolean shouldSummarize(ChatSession session, List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return false;
        }
        ModelConfig chatConfig = modelConfigService.activeChatOrDefault();
        int totalTokens = memorySnapshotService.estimateMessageTokens(messages, chatConfig);
        int budgetTokens = memorySnapshotService.historyBudgetTokens(chatConfig);
        return messages.size() > memoryProperties.resolvedSummarizeAfterMessages()
                || totalTokens > budgetTokens
                || (session.summaryMessageSequence() > 0 && totalTokens > budgetTokens);
    }

    public int estimateMessageTokens(ChatMessage message) {
        return memorySnapshotService.estimateMessageTokens(message, modelConfigService.activeChatOrDefault());
    }

    public int estimateMessagesTokens(List<ChatMessage> messages) {
        return memorySnapshotService.estimateMessageTokens(messages, modelConfigService.activeChatOrDefault());
    }

    private static ChatContextUsageResponse emptyUsage(ModelConfig chatConfig) {
        int contextWindowTokens = Math.max(1, chatConfig.resolvedContextWindowTokens());
        return new ChatContextUsageResponse(
                contextWindowTokens,
                0,
                contextWindowTokens,
                0.0,
                false,
                0,
                0,
                0,
                0,
                0,
                "empty"
        );
    }
}
