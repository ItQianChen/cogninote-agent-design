package com.itqianchen.agentdesign.domain.dto.chat;


import com.itqianchen.agentdesign.domain.entity.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import java.util.List;

/**
 * Chat Message 响应 定义返回给前端的 聊天会话 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record ChatMessageResponse(
        String id,
        ChatMessageRole role,
        String content,
        String reasoningContent,
        String status,
        String requestId,
        SearchMode retrievalMode,
        List<RagSourceResponse> sources,
        List<ChatReferenceResponse> references,
        long createdAt
) {

    /**
     * 把领域消息转成前端状态。
     *
     * <p>status 暴露为小写字符串，匹配 Pinia store 的消息状态约定。</p>
     */
    public static ChatMessageResponse from(
            ChatMessage message,
            List<RagSourceResponse> sources,
            List<ChatReferenceResponse> references
    ) {
        return new ChatMessageResponse(
                message.id(),
                message.role(),
                message.content(),
                message.reasoningContent(),
                message.status().name().toLowerCase(),
                message.requestId(),
                message.retrievalMode(),
                sources,
                references,
                message.createdAt()
        );
    }
}

