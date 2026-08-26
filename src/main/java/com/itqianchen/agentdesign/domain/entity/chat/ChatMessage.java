package com.itqianchen.agentdesign.domain.entity.chat;


import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.enums.agent.AgentType;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;

/**
 * 已持久化的聊天消息。
 *
 * <p>sequence 是同一 conversationId 内的稳定顺序号，长会话摘要和历史裁剪都依赖它。
 * sourcesJson 保存回答产生时的 RAG 来源快照；referencesJson 保存用户引用的助手片段，
 * 二者都不能用当前索引或消息文本反推替代。</p>
 */
public record ChatMessage(
        String id,
        String conversationId,
        int sequence,
        ChatMessageRole role,
        String content,
        String reasoningContent,
        ChatMessageStatus status,
        String requestId,
        AgentType agentType,
        SearchMode retrievalMode,
        String sourcesJson,
        String referencesJson,
        int tokenEstimate,
        long createdAt
) {
    public ChatMessage(
            String id, String conversationId, int sequence, ChatMessageRole role, String content,
            ChatMessageStatus status, String requestId, AgentType agentType, SearchMode retrievalMode,
            String sourcesJson, String referencesJson, int tokenEstimate, long createdAt
    ) {
        this(id, conversationId, sequence, role, content, null, status, requestId, agentType, retrievalMode,
                sourcesJson, referencesJson, tokenEstimate, createdAt);
    }
}
