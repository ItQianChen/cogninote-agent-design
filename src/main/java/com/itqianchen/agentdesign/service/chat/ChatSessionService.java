package com.itqianchen.agentdesign.service.chat;

import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.chat.ChatSession;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatMessageResponse;
import com.itqianchen.agentdesign.dto.chat.ChatSessionCreateRequest;
import com.itqianchen.agentdesign.dto.chat.ChatSessionResponse;
import com.itqianchen.agentdesign.dto.chat.ChatSessionUpdateRequest;
import com.itqianchen.agentdesign.dto.chat.RagSourceResponse;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatSessionService {

    private static final int SUMMARY_MAX_CHARS = 4000;

    private final ChatSessionRepository chatSessionRepository;
    private final RagSourcesJsonCodec ragSourcesJsonCodec;
    private final TokenEstimator tokenEstimator;
    private final ChatMemoryProperties memoryProperties;

    public ChatSessionService(
            ChatSessionRepository chatSessionRepository,
            RagSourcesJsonCodec ragSourcesJsonCodec,
            TokenEstimator tokenEstimator,
            ChatMemoryProperties memoryProperties
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.ragSourcesJsonCodec = ragSourcesJsonCodec;
        this.tokenEstimator = tokenEstimator;
        this.memoryProperties = memoryProperties;
    }

    public List<ChatSessionResponse> listSessions() {
        return chatSessionRepository.findActiveSessions().stream()
                .map(session -> ChatSessionResponse.summary(
                        session,
                        chatSessionRepository.countMessages(session.id())
                ))
                .toList();
    }

    @Transactional
    public ChatSessionResponse createSession(ChatSessionCreateRequest request) {
        long now = System.currentTimeMillis();
        ChatSession session = chatSessionRepository.create(
                request == null ? null : request.title(),
                request == null || request.useKnowledgeBase() == null || request.useKnowledgeBase(),
                request == null ? SearchMode.HYBRID : request.mode(),
                request == null || request.topK() == null ? 8 : request.topK(),
                now
        );
        return ChatSessionResponse.from(session, List.of());
    }

    public ChatSessionResponse getSession(String conversationId) {
        ChatSession session = requireSession(conversationId);
        return ChatSessionResponse.from(session, messageResponses(conversationId));
    }

    @Transactional
    public ChatSessionResponse updateSession(String conversationId, ChatSessionUpdateRequest request) {
        ChatSession existing = requireSession(conversationId);
        chatSessionRepository.updateOptions(
                conversationId,
                request.title(),
                request.useKnowledgeBase() == null ? existing.useKnowledgeBase() : request.useKnowledgeBase(),
                request.mode() == null ? existing.retrievalMode() : request.mode(),
                request.topK() == null ? existing.topK() : request.topK(),
                System.currentTimeMillis()
        );
        return getSession(conversationId);
    }

    @Transactional
    public void deleteSession(String conversationId) {
        if (!chatSessionRepository.softDelete(conversationId, System.currentTimeMillis())) {
            throw new ResourceNotFoundException("Chat session not found: " + conversationId);
        }
    }

    @Transactional
    public ChatSessionResponse clearMessages(String conversationId) {
        requireSession(conversationId);
        chatSessionRepository.clearMessages(conversationId, System.currentTimeMillis());
        return getSession(conversationId);
    }

    @Transactional
    public ChatSession ensureSession(
            String conversationId,
            String fallbackTitle,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK
    ) {
        return chatSessionRepository.ensureSession(
                conversationId,
                fallbackTitle,
                useKnowledgeBase,
                mode,
                topK,
                System.currentTimeMillis()
        );
    }

    @Transactional
    public ChatMessage appendUserMessage(
            String conversationId,
            String content,
            String requestId,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK
    ) {
        long now = System.currentTimeMillis();
        ChatSession session = chatSessionRepository.ensureSession(
                conversationId,
                titleFromQuestion(content),
                useKnowledgeBase,
                mode,
                topK,
                now
        );
        if ("新对话".equals(session.title()) && chatSessionRepository.countMessages(session.id()) == 0) {
            chatSessionRepository.updateOptions(
                    session.id(),
                    titleFromQuestion(content),
                    useKnowledgeBase,
                    mode,
                    topK,
                    now
            );
        }
        return chatSessionRepository.appendMessage(
                session.id(),
                ChatMessageRole.USER,
                content,
                ChatMessageStatus.DONE,
                requestId,
                null,
                null,
                tokenEstimator.estimate(content),
                now
        );
    }

    @Transactional
    public void appendAssistantDone(
            String conversationId,
            String content,
            String requestId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, requestId, retrievalMode, sources, ChatMessageStatus.DONE);
        refreshSummaryIfNeeded(conversationId);
    }

    @Transactional
    public void appendAssistantStopped(
            String conversationId,
            String content,
            String requestId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, requestId, retrievalMode, sources, ChatMessageStatus.STOPPED);
        refreshSummaryIfNeeded(conversationId);
    }

    @Transactional
    public void appendAssistantError(
            String conversationId,
            String content,
            String requestId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, requestId, retrievalMode, sources, ChatMessageStatus.ERROR);
    }

    public ChatSession requireSession(String conversationId) {
        return chatSessionRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + conversationId));
    }

    private void appendAssistant(
            String conversationId,
            String content,
            String requestId,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources,
            ChatMessageStatus status
    ) {
        if (content == null || content.isBlank()) {
            return;
        }
        chatSessionRepository.appendMessage(
                conversationId,
                ChatMessageRole.ASSISTANT,
                content,
                status,
                requestId,
                retrievalMode,
                ragSourcesJsonCodec.encode(sources),
                tokenEstimator.estimate(content),
                System.currentTimeMillis()
        );
    }

    private List<ChatMessageResponse> messageResponses(String conversationId) {
        return chatSessionRepository.findMessages(conversationId).stream()
                .map(message -> ChatMessageResponse.from(message, ragSourcesJsonCodec.decode(message.sourcesJson())))
                .toList();
    }

    private void refreshSummaryIfNeeded(String conversationId) {
        ChatSession session = chatSessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return;
        }
        List<ChatMessage> messages = chatSessionRepository.findMessages(conversationId);
        if (messages.size() <= memoryProperties.resolvedSummarizeAfterMessages()
                && totalTokens(messages) <= memoryProperties.resolvedMaxHistoryTokens()) {
            return;
        }

        int protectedRecentMessages = memoryProperties.resolvedMinimumRecentMessages();
        int coverUntilIndex = Math.max(0, messages.size() - protectedRecentMessages);
        if (coverUntilIndex == 0) {
            return;
        }

        List<ChatMessage> covered = messages.subList(0, coverUntilIndex);
        int coveredSequence = covered.getLast().sequence();
        if (coveredSequence <= session.summaryMessageSequence()) {
            return;
        }
        /*
         * SQLite 保存全量原文；summary 只是模型输入预算层的压缩视图。
         * 摘要只覆盖较早消息，最近若干条仍保留原文，避免长会话追问时丢失细节。
         */
        chatSessionRepository.updateSummary(
                conversationId,
                buildExtractiveSummary(covered),
                coveredSequence,
                System.currentTimeMillis()
        );
    }

    private int totalTokens(List<ChatMessage> messages) {
        int tokens = 0;
        for (ChatMessage message : messages) {
            tokens += Math.max(1, message.tokenEstimate());
        }
        return tokens;
    }

    private static String buildExtractiveSummary(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder("以下是本会话较早内容的滚动摘要，按时间顺序保留关键事实：\n");
        for (ChatMessage message : messages) {
            String role = message.role() == ChatMessageRole.USER ? "用户" : "助手";
            String line = "- %s：%s%n".formatted(role, compact(message.content()));
            if (builder.length() + line.length() > SUMMARY_MAX_CHARS) {
                builder.append("- 更早的细节仍保存在 SQLite 原始消息中，本轮仅注入摘要视图。\n");
                break;
            }
            builder.append(line);
        }
        return builder.toString().trim();
    }

    private static String compact(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    private static String titleFromQuestion(String question) {
        String normalized = compact(question);
        if (normalized.isBlank()) {
            return "新对话";
        }
        return normalized.length() <= 18 ? normalized : normalized.substring(0, 18) + "...";
    }
}
