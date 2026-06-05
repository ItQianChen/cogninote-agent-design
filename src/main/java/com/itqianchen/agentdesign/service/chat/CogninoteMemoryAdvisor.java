package com.itqianchen.agentdesign.service.chat;

import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

@Component
public class CogninoteMemoryAdvisor implements BaseAdvisor {

    public static final String MAX_MESSAGE_SEQUENCE = "cogninote.memory.maxMessageSequence";

    private final ConversationMemorySnapshotService memorySnapshotService;

    public CogninoteMemoryAdvisor(ConversationMemorySnapshotService memorySnapshotService) {
        this.memorySnapshotService = memorySnapshotService;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest request, AdvisorChain chain) {
        String conversationId = stringParam(request, ChatMemory.CONVERSATION_ID);
        if (conversationId == null || conversationId.isBlank()) {
            return request;
        }

        ConversationMemorySnapshot snapshot = memorySnapshotService.snapshot(
                conversationId,
                intParam(request, MAX_MESSAGE_SEQUENCE, Integer.MAX_VALUE)
        );
        if ((snapshot.summary() == null || snapshot.summary().isBlank()) && snapshot.recentMessages().isEmpty()) {
            return request;
        }

        List<Message> promptMessages = request.prompt().getInstructions();
        List<Message> merged = new ArrayList<>(promptMessages.size() + snapshot.recentMessages().size() + 1);

        for (Message message : promptMessages) {
            if (message.getMessageType() == MessageType.SYSTEM) {
                merged.add(message);
            }
        }
        if (snapshot.summary() != null && !snapshot.summary().isBlank()) {
            merged.add(new SystemMessage("""
                    以下是当前会话较早内容的摘要。它用于保持长会话上下文，不能替代本轮用户问题：
                    %s
                    """.formatted(snapshot.summary())));
        }
        /*
         * 只注入当前用户消息之前的历史。当前问题仍由 ChatClient 的 user(...) 提供，
         * 否则模型输入会重复出现同一个问题，长会话下尤其容易产生跑题回答。
         */
        merged.addAll(snapshot.recentMessages());
        for (Message message : promptMessages) {
            if (message.getMessageType() != MessageType.SYSTEM) {
                merged.add(message);
            }
        }

        return request.mutate()
                .prompt(new Prompt(merged, request.prompt().getOptions()))
                .build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse response, AdvisorChain chain) {
        return response;
    }

    @Override
    public int getOrder() {
        return Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER;
    }

    private static String stringParam(ChatClientRequest request, String key) {
        Object value = request.context().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intParam(ChatClientRequest request, String key, int defaultValue) {
        Object value = request.context().get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
