package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import java.util.List;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

@Component
public class SQLiteChatMemory implements ChatMemory {

    private final ChatSessionRepository chatSessionRepository;
    private final TokenEstimator tokenEstimator;

    public SQLiteChatMemory(ChatSessionRepository chatSessionRepository, TokenEstimator tokenEstimator) {
        this.chatSessionRepository = chatSessionRepository;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        chatSessionRepository.ensureSession(conversationId, "新对话", true, SearchMode.HYBRID, 8, now);
        for (Message message : messages) {
            add(conversationId, message);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        return chatSessionRepository.findMessages(conversationId).stream()
                .filter(message -> message.role() == ChatMessageRole.USER || message.role() == ChatMessageRole.ASSISTANT)
                .map(SQLiteChatMemory::toSpringMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        chatSessionRepository.clearMessages(conversationId, System.currentTimeMillis());
    }

    @Override
    public void add(String conversationId, Message message) {
        if (message == null || message.getText() == null || message.getText().isBlank()) {
            return;
        }
        ChatMessageRole role = message.getMessageType() == MessageType.ASSISTANT
                ? ChatMessageRole.ASSISTANT
                : ChatMessageRole.USER;
        chatSessionRepository.appendMessage(
                conversationId,
                role,
                message.getText(),
                ChatMessageStatus.DONE,
                null,
                null,
                null,
                tokenEstimator.estimate(message.getText()),
                System.currentTimeMillis()
        );
    }

    private static Message toSpringMessage(ChatMessage message) {
        if (message.role() == ChatMessageRole.ASSISTANT) {
            return new AssistantMessage(message.content());
        }
        return new UserMessage(message.content());
    }
}
