package com.itqianchen.agentdesign.repository.chat;


import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.entity.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.entity.chat.ChatSession;
import com.itqianchen.agentdesign.domain.enums.agent.AgentType;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSessionResponse;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionMapper;
import com.itqianchen.agentdesign.mapper.chat.ChatSessionSummaryRow;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 聊天会话仓储。
 *
 * <p>对外隐藏 chat_sessions 与 chat_messages 的一对多表结构，并集中维护会话 id、消息 sequence
 * 和删除时的消息清理策略。</p>
 */
@Repository
public class ChatSessionRepository {

    private final ChatSessionMapper chatSessionMapper;

    /**
     * 注入聊天会话 Mapper。
     *
     * @param chatSessionMapper SQLite 会话和消息访问接口
     */
    public ChatSessionRepository(ChatSessionMapper chatSessionMapper) {
        this.chatSessionMapper = chatSessionMapper;
    }

    /**
     * 查询未删除的完整会话列表。
     *
     * @return 活跃会话领域对象
     */
    public List<ChatSession> findActiveSessions() {
        return chatSessionMapper.findActiveSessions();
    }

    /**
     * 查询侧栏使用的轻量会话摘要。
     *
     * <p>该查询会携带消息数量，避免前端为了列表展示逐个读取完整消息。</p>
     *
     * @return 会话摘要响应列表
     */
    public List<ChatSessionResponse> findActiveSessionSummaries() {
        return chatSessionMapper.findActiveSessionSummaries().stream()
                .map(ChatSessionRepository::toSummaryResponse)
                .toList();
    }

    /**
     * 按 ID 查询未删除会话。
     *
     * @param id 会话 ID
     * @return 找到的会话；不存在或已删除时为空
     */
    public Optional<ChatSession> findById(String id) {
        return chatSessionMapper.findById(id).stream().findFirst();
    }

    /**
     * 创建使用随机 ID 的会话。
     *
     * @param title 会话标题；空值会落为默认标题
     * @param useKnowledgeBase 是否启用知识库检索
     * @param mode 检索模式；空值使用 HYBRID
     * @param topK 检索数量，会归一到 1 到 50
     * @param now 创建和更新时间戳
     * @return 新建会话
     */
    public ChatSession create(String title, boolean useKnowledgeBase, SearchMode mode, int topK, long now) {
        return create(UUID.randomUUID().toString(), title, useKnowledgeBase, mode, topK, now);
    }

    /**
     * 创建指定 ID 的会话。
     *
     * <p>SSE 流会提前把 conversationId 发给前端，因此首次落库时必须允许使用外部给定 ID。</p>
     *
     * @param id 指定会话 ID；为空时生成随机 ID
     * @param title 会话标题；空值会落为默认标题
     * @param useKnowledgeBase 是否启用知识库检索
     * @param mode 检索模式；空值使用 HYBRID
     * @param topK 检索数量，会归一到 1 到 50
     * @param now 创建和更新时间戳
     * @return 新建会话或已存在的活跃会话
     */
    public ChatSession create(
            String id,
            String title,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long now
    ) {
        ChatSession session = new ChatSession(
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                title == null || title.isBlank() ? "新对话" : title.trim(),
                null,
                0,
                useKnowledgeBase,
                mode == null ? SearchMode.HYBRID : mode,
                normalizeTopK(topK),
                false,
                now,
                now
        );
        if (chatSessionMapper.insertSession(session) > 0) {
            return session;
        }
        return findById(session.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Chat session id already exists but is not active: " + session.id()
                ));
    }

    /**
     * 确保聊天流对应的会话已经存在。
     *
     * <p>已有会话会同步本轮检索选项；不存在但前端已持有 ID 时使用同一个 ID 创建，避免消息归属分裂。</p>
     *
     * @param conversationId 前端或请求指定的会话 ID
     * @param fallbackTitle 新会话默认标题
     * @param useKnowledgeBase 是否启用知识库检索
     * @param mode 检索模式
     * @param topK 检索数量
     * @param now 当前时间戳
     * @return 可写入消息的会话
     */
    public ChatSession ensureSession(
            String conversationId,
            String fallbackTitle,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long now
    ) {
        if (conversationId != null && !conversationId.isBlank()) {
            Optional<ChatSession> existing = findById(conversationId);
            if (existing.isPresent()) {
                updateOptions(conversationId, null, useKnowledgeBase, mode, topK, now);
                return findById(conversationId).orElse(existing.get());
            }
            /*
             * SSE meta 会把 conversationId 提前返回给前端。首次发送时必须用同一个
             * id 创建 SQLite 会话，否则前端持有的会话 id 与落库消息会分叉。
             */
            ChatSession session = create(conversationId, fallbackTitle, useKnowledgeBase, mode, topK, now);
            updateOptions(conversationId, null, useKnowledgeBase, mode, topK, now);
            return findById(conversationId).orElse(session);
        }
        return create(fallbackTitle, useKnowledgeBase, mode, topK, now);
    }

    /**
     * 更新会话标题和检索选项。
     *
     * <p>标题为空时不覆盖原标题；检索选项会影响后续消息，不回写历史消息快照。</p>
     *
     * @param id 会话 ID
     * @param title 新标题；为空时忽略
     * @param useKnowledgeBase 是否启用知识库检索
     * @param mode 检索模式；空值使用 HYBRID
     * @param topK 检索数量
     * @param updatedAt 更新时间戳
     */
    public void updateOptions(
            String id,
            String title,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            long updatedAt
    ) {
        chatSessionMapper.updateOptions(
                id,
                title == null || title.isBlank() ? null : title.trim(),
                useKnowledgeBase,
                (mode == null ? SearchMode.HYBRID : mode).name(),
                normalizeTopK(topK),
                updatedAt
        );
    }

    /**
     * 更新会话摘要及其覆盖到的消息序号。
     *
     * @param id 会话 ID
     * @param summary 摘要文本
     * @param coveredSequence 摘要覆盖到的最大消息序号
     * @param updatedAt 更新时间戳
     */
    public void updateSummary(String id, String summary, int coveredSequence, long updatedAt) {
        chatSessionMapper.updateSummary(id, summary, coveredSequence, updatedAt);
    }

    /**
     * 删除会话并清理其消息。
     *
     * <p>这里显式删除消息，不依赖 SQLite 外键设置，避免不同运行环境留下孤儿消息。</p>
     *
     * @param id 会话 ID
     * @return 是否删除了活跃会话
     */
    public boolean deleteSession(String id) {
        /*
         * 删除会话是用户可见的销毁操作，不能只隐藏 chat_sessions。
         * 先删除会话确认 id 有效，再显式清消息，避免依赖 SQLite 外键开关导致历史消息残留。
         */
        if (chatSessionMapper.deleteSession(id) == 0) {
            return false;
        }
        chatSessionMapper.deleteMessages(id);
        return true;
    }

    /**
     * 清空会话消息并重置会话摘要状态。
     *
     * @param conversationId 会话 ID
     * @param updatedAt 更新时间戳
     */
    public void clearMessages(String conversationId, long updatedAt) {
        chatSessionMapper.deleteMessages(conversationId);
        chatSessionMapper.resetSessionMessages(conversationId, updatedAt);
    }

    /**
     * 查询会话全部消息。
     *
     * @param conversationId 会话 ID
     * @return 按 sequence 排序的消息列表
     */
    public List<ChatMessage> findMessages(String conversationId) {
        return chatSessionMapper.findMessages(conversationId);
    }

    /**
     * 统计会话消息数量。
     *
     * @param conversationId 会话 ID
     * @return 消息数量
     */
    public int countMessages(String conversationId) {
        return chatSessionMapper.countMessages(conversationId);
    }

    /**
     * 查询指定 sequence 之后的消息。
     *
     * <p>用于记忆摘要增量更新，调用方依赖 sequence 单调递增。</p>
     *
     * @param conversationId 会话 ID
     * @param sequence 已处理到的消息序号
     * @return sequence 更大的消息列表
     */
    public List<ChatMessage> findMessagesAfter(String conversationId, int sequence) {
        return chatSessionMapper.findMessagesAfter(conversationId, sequence);
    }

    /**
     * 追加一条会话消息。
     *
     * <p>sequence 在数据库当前最大值基础上递增，同时 touch 会话更新时间，保证侧栏按最新消息排序。</p>
     *
     * @param conversationId 会话 ID
     * @param role 消息角色
     * @param content 消息内容
     * @param status 消息状态
     * @param requestId 流式请求 ID
     * @param agentType 生成该消息的 Agent 类型
     * @param retrievalMode 本轮检索模式
     * @param sourcesJson RAG 来源快照 JSON
     * @param referencesJson 用户引用助手片段 JSON
     * @param tokenEstimate 估算 token 数
     * @param createdAt 创建时间戳
     * @return 已落库消息
     */
    public ChatMessage appendMessage(
            String conversationId,
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
        int nextSequence = chatSessionMapper.nextMessageSequence(conversationId);
        ChatMessage message = new ChatMessage(
                UUID.randomUUID().toString(),
                conversationId,
                nextSequence,
                role,
                content,
                reasoningContent,
                status,
                requestId,
                agentType,
                retrievalMode,
                sourcesJson,
                referencesJson,
                tokenEstimate,
                createdAt
        );
        chatSessionMapper.insertMessage(message);
        chatSessionMapper.touchSession(conversationId, createdAt);
        return message;
    }

    public ChatMessage appendMessage(
            String conversationId, ChatMessageRole role, String content, ChatMessageStatus status, String requestId,
            AgentType agentType, SearchMode retrievalMode, String sourcesJson, String referencesJson,
            int tokenEstimate, long createdAt
    ) {
        return appendMessage(conversationId, role, content, null, status, requestId, agentType, retrievalMode,
                sourcesJson, referencesJson, tokenEstimate, createdAt);
    }

    /**
     * 将 SQL 摘要行恢复为前端响应。
     *
     * @param row 包含会话字段和消息计数的聚合行
     * @return 会话摘要响应
     */
    private static ChatSessionResponse toSummaryResponse(ChatSessionSummaryRow row) {
        ChatSession session = new ChatSession(
                row.id(),
                row.title(),
                row.summary(),
                row.summaryMessageSequence(),
                row.useKnowledgeBase(),
                row.retrievalMode(),
                row.topK(),
                row.deleted(),
                row.createdAt(),
                row.updatedAt()
        );
        return ChatSessionResponse.summary(session, row.messageCount());
    }

    /**
     * 归一化会话检索数量。
     *
     * @param topK 请求值
     * @return 限制在 1 到 50 的数量
     */
    private static int normalizeTopK(int topK) {
        return Math.clamp(topK, 1, 50);
    }
}
