package com.itqianchen.agentdesign.service.chat;


import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.enums.agent.AgentType;
import com.itqianchen.agentdesign.domain.properties.chat.ChatMemoryProperties;
import com.itqianchen.agentdesign.domain.entity.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageRole;
import com.itqianchen.agentdesign.domain.enums.chat.ChatMessageStatus;
import com.itqianchen.agentdesign.domain.entity.chat.ChatSession;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.dto.chat.ChatMessageResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatReferenceRequest;
import com.itqianchen.agentdesign.domain.dto.chat.ChatReferenceResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSessionCreateRequest;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSessionResponse;
import com.itqianchen.agentdesign.domain.dto.chat.ChatSessionUpdateRequest;
import com.itqianchen.agentdesign.domain.dto.chat.RagSourceResponse;
import com.itqianchen.agentdesign.repository.chat.ChatSessionRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 维护聊天会话、消息落库和上下文用量快照。
 *
 * <p>助手回复在流结束后按 DONE/STOPPED/ERROR 状态落库；长会话摘要只是模型输入预算视图，
 * SQLite 仍保留完整原文。</p>
 */
@Service
public class ChatSessionService {

    private static final int SUMMARY_MAX_CHARS = 4000;

    private final ChatSessionRepository chatSessionRepository;
    private final RagSourcesJsonCodec ragSourcesJsonCodec;
    private final ChatReferencesJsonCodec chatReferencesJsonCodec;
    private final ChatReferenceSanitizer chatReferenceSanitizer;
    private final TokenEstimator tokenEstimator;
    private final ChatMemoryProperties memoryProperties;
    private final ChatContextUsageService contextUsageService;
    private final ModelConfigService modelConfigService;

    /**
     * 注入会话、来源编解码、token 估算和模型配置依赖。
     *
     * @param chatSessionRepository 会话仓储
     * @param ragSourcesJsonCodec RAG 来源快照编解码器
     * @param chatReferencesJsonCodec 引用片段编解码器
     * @param chatReferenceSanitizer 引用片段清洗器
     * @param tokenEstimator token 估算器
     * @param memoryProperties 记忆窗口配置
     * @param contextUsageService 上下文用量服务
     * @param modelConfigService 模型配置服务
     */
    public ChatSessionService(
            ChatSessionRepository chatSessionRepository,
            RagSourcesJsonCodec ragSourcesJsonCodec,
            ChatReferencesJsonCodec chatReferencesJsonCodec,
            ChatReferenceSanitizer chatReferenceSanitizer,
            TokenEstimator tokenEstimator,
            ChatMemoryProperties memoryProperties,
            ChatContextUsageService contextUsageService,
            ModelConfigService modelConfigService
    ) {
        this.chatSessionRepository = chatSessionRepository;
        this.ragSourcesJsonCodec = ragSourcesJsonCodec;
        this.chatReferencesJsonCodec = chatReferencesJsonCodec;
        this.chatReferenceSanitizer = chatReferenceSanitizer;
        this.tokenEstimator = tokenEstimator;
        this.memoryProperties = memoryProperties;
        this.contextUsageService = contextUsageService;
        this.modelConfigService = modelConfigService;
    }

    /**
     * 查询会话列表并附带上下文用量。
     *
     * @return 会话摘要列表
     */
    public List<ChatSessionResponse> listSessions() {
        return chatSessionRepository.findActiveSessionSummaries().stream()
                .map(this::withContextUsage)
                .toList();
    }

    /**
     * 创建一个新的聊天会话。
     *
     * <p>请求体允许为空，默认启用知识库和 HYBRID 检索，保持前端“新建对话”轻量调用。</p>
     *
     * @param request 可选创建参数
     * @return 新会话详情
     */
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
        return withContextUsage(ChatSessionResponse.from(session, List.of()));
    }

    /**
     * 查询会话详情。
     *
     * @param conversationId 会话 ID
     * @return 会话和消息响应
     */
    public ChatSessionResponse getSession(String conversationId) {
        ChatSession session = requireSession(conversationId);
        return withContextUsage(ChatSessionResponse.from(session, messageResponses(conversationId)));
    }

    /**
     * 更新会话标题和检索选项。
     *
     * <p>请求未提供的字段会沿用现有会话值，不会重写历史消息或来源快照。</p>
     *
     * @param conversationId 会话 ID
     * @param request 更新请求
     * @return 更新后的会话详情
     */
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

    /**
     * 删除会话。
     *
     * @param conversationId 会话 ID
     * @throws ResourceNotFoundException 当会话不存在时抛出
     */
    @Transactional
    public void deleteSession(String conversationId) {
        if (!chatSessionRepository.deleteSession(conversationId)) {
            throw new ResourceNotFoundException("Chat session not found: " + conversationId);
        }
    }

    /**
     * 清空会话消息并保留会话配置。
     *
     * @param conversationId 会话 ID
     * @return 清空后的会话详情
     */
    @Transactional
    public ChatSessionResponse clearMessages(String conversationId) {
        requireSession(conversationId);
        chatSessionRepository.clearMessages(conversationId, System.currentTimeMillis());
        return getSession(conversationId);
    }

    /**
     * 确保聊天流写入前会话存在。
     *
     * @param conversationId 会话 ID
     * @param fallbackTitle 新会话标题兜底
     * @param useKnowledgeBase 是否启用知识库检索
     * @param mode 检索模式
     * @param topK 检索数量
     * @return 可写入的会话
     */
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

    /**
     * 写入用户消息。
     *
     * <p>首次真实消息会把默认标题更新为问题摘要，避免仅打开页面就产生有标题的脏会话。</p>
     *
     * @param conversationId 会话 ID
     * @param content 用户输入
     * @param requestId 本轮请求 ID
     * @param agentType Agent 类型
     * @param useKnowledgeBase 是否启用知识库
     * @param mode 检索模式
     * @param topK 检索数量
     * @param references 用户引用的助手片段
     * @return 已落库的用户消息
     */
    @Transactional
    public ChatMessage appendUserMessage(
            String conversationId,
            String content,
            String requestId,
            AgentType agentType,
            boolean useKnowledgeBase,
            SearchMode mode,
            int topK,
            List<ChatReferenceRequest> references
    ) {
        long now = System.currentTimeMillis();
        List<ChatReferenceResponse> sanitizedReferences = chatReferenceSanitizer.sanitizeRequests(references);
        String referencesJson = chatReferencesJsonCodec.encode(sanitizedReferences);
        ChatSession session = chatSessionRepository.ensureSession(
                conversationId,
                titleFromQuestion(content),
                useKnowledgeBase,
                mode,
                topK,
                now
        );
        // 空白草稿会话在第一条真实用户消息出现时才确定标题，避免“打开应用”生成脏会话。
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
                agentType,
                null,
                null,
                referencesJson,
                estimateChatMessage(ChatReferencePromptFormatter.formatUserContent(content, sanitizedReferences)),
                now
        );
    }

    /**
     * 写入正常完成的助手回复并按需刷新摘要。
     *
     * @param conversationId 会话 ID
     * @param content 助手回复
     * @param requestId 本轮请求 ID
     * @param agentType Agent 类型
     * @param retrievalMode 实际检索模式
     * @param sources RAG 来源快照
     */
    @Transactional
    public void appendAssistantDone(
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            AgentType agentType,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, reasoningContent, requestId, agentType, retrievalMode, sources, ChatMessageStatus.DONE);
        refreshSummaryIfNeeded(conversationId);
    }

    /**
     * 写入用户主动停止后的助手部分回复并按需刷新摘要。
     *
     * @param conversationId 会话 ID
     * @param content 已生成内容
     * @param requestId 本轮请求 ID
     * @param agentType Agent 类型
     * @param retrievalMode 实际检索模式
     * @param sources RAG 来源快照
     */
    @Transactional
    public void appendAssistantStopped(
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            AgentType agentType,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, reasoningContent, requestId, agentType, retrievalMode, sources, ChatMessageStatus.STOPPED);
        refreshSummaryIfNeeded(conversationId);
    }

    /**
     * 写入异常结束的助手回复。
     *
     * <p>异常回复不触发摘要刷新，避免把错误提示当成长期上下文压缩依据。</p>
     *
     * @param conversationId 会话 ID
     * @param content 错误前已生成内容或错误提示
     * @param requestId 本轮请求 ID
     * @param agentType Agent 类型
     * @param retrievalMode 实际检索模式
     * @param sources RAG 来源快照
     */
    @Transactional
    public void appendAssistantError(
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            AgentType agentType,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources
    ) {
        appendAssistant(conversationId, content, reasoningContent, requestId, agentType, retrievalMode, sources, ChatMessageStatus.ERROR);
    }

    /**
     * 读取会话，不存在时抛出统一 404 异常。
     *
     * @param conversationId 会话 ID
     * @return 会话领域对象
     */
    public ChatSession requireSession(String conversationId) {
        return chatSessionRepository.findById(conversationId)
                .orElseThrow(() -> new ResourceNotFoundException("Chat session not found: " + conversationId));
    }

    /**
     * 按状态写入助手消息。
     *
     * <p>空内容不落库，防止停止或异常路径产生不可见消息并污染上下文预算。</p>
     *
     * @param conversationId 会话 ID
     * @param content 助手内容
     * @param requestId 本轮请求 ID
     * @param agentType Agent 类型
     * @param retrievalMode 实际检索模式
     * @param sources RAG 来源快照
     * @param status 消息状态
     */
    private void appendAssistant(
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            AgentType agentType,
            SearchMode retrievalMode,
            List<RagSourceResponse> sources,
            ChatMessageStatus status
    ) {
        if ((content == null || content.isBlank()) && (reasoningContent == null || reasoningContent.isBlank())) {
            // 空回复不落库，避免停止或异常路径产生看不见但会污染上下文预算的消息。
            return;
        }
        chatSessionRepository.appendMessage(
                conversationId,
                ChatMessageRole.ASSISTANT,
                content,
                reasoningContent,
                status,
                requestId,
                agentType,
                retrievalMode,
                ragSourcesJsonCodec.encode(sources),
                null,
                estimateChatMessage(content),
                System.currentTimeMillis()
        );
    }

    /**
     * 将会话消息转换为前端响应。
     *
     * @param conversationId 会话 ID
     * @return 消息响应列表
     */
    private List<ChatMessageResponse> messageResponses(String conversationId) {
        return chatSessionRepository.findMessages(conversationId).stream()
                .map(message -> ChatMessageResponse.from(
                        message,
                        ragSourcesJsonCodec.decode(message.sourcesJson()),
                        chatReferencesJsonCodec.decode(message.referencesJson())
                ))
                .toList();
    }

    /**
     * 构造指定消息进入模型上下文时的内容。
     *
     * @param message 聊天消息
     * @return 用户消息会拼接引用块，其他消息返回原内容
     */
    public String modelContent(ChatMessage message) {
        if (message == null) {
            return "";
        }
        if (message.role() != ChatMessageRole.USER) {
            return message.content();
        }
        return ChatReferencePromptFormatter.formatUserContent(
                message.content(),
                chatReferencesJsonCodec.decode(message.referencesJson())
        );
    }

    /**
     * 在历史消息超过预算时刷新滚动摘要。
     *
     * <p>摘要只覆盖较早消息，SQLite 保留完整原文；最近消息仍以原文进入模型，减少追问时的信息损失。</p>
     *
     * @param conversationId 会话 ID
     */
    private void refreshSummaryIfNeeded(String conversationId) {
        ChatSession session = chatSessionRepository.findById(conversationId).orElse(null);
        if (session == null) {
            return;
        }
        List<ChatMessage> messages = chatSessionRepository.findMessages(conversationId);
        if (!contextUsageService.shouldSummarize(session, messages)) {
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

    /**
     * 查询单个会话的上下文用量。
     *
     * @param conversationId 会话 ID
     * @return 上下文用量响应
     */
    public ChatContextUsageResponse contextUsage(String conversationId) {
        return contextUsageService.usage(conversationId);
    }

    /**
     * 给会话响应补充上下文用量。
     *
     * @param response 会话响应
     * @return 带上下文用量的会话响应
     */
    private ChatSessionResponse withContextUsage(ChatSessionResponse response) {
        return response.withContextUsage(contextUsageService.usage(response.id()));
    }

    /**
     * 按当前 Chat 模型配置估算消息 token。
     *
     * @param content 消息内容
     * @return 估算 token 数
     */
    private int estimateChatMessage(String content) {
        ModelConfig chatConfig = modelConfigService.activeChatOrDefault();
        return tokenEstimator.estimateChatMessage(content, chatConfig);
    }

    /**
     * 构建较早消息的抽取式摘要。
     *
     * <p>摘要有字符上限，达到上限时明确提示原文仍在 SQLite 中，避免维护者误以为历史被删除。</p>
     *
     * @param messages 被摘要覆盖的消息
     * @return 摘要文本
     */
    private String buildExtractiveSummary(List<ChatMessage> messages) {
        StringBuilder builder = new StringBuilder(
                "以下是本会话较早内容的滚动摘要，按时间顺序保留关键事实；每条都带有当时的 Agent 模式：\n");
        for (ChatMessage message : messages) {
            String role = message.role() == ChatMessageRole.USER ? "用户" : "助手";
            String agentType = message.agentType() == null ? "UNKNOWN" : message.agentType().name();
            String line = "- [%s] %s：%s%n".formatted(agentType, role, compact(modelContent(message)));
            if (builder.length() + line.length() > SUMMARY_MAX_CHARS) {
                builder.append("- 更早的细节仍保存在 SQLite 原始消息中，本轮仅注入摘要视图。\n");
                break;
            }
            builder.append(line);
        }
        return builder.toString().trim();
    }

    /**
     * 压缩文本用于标题或摘要行。
     *
     * @param content 原始文本
     * @return 单行短文本
     */
    private static String compact(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "...";
    }

    /**
     * 从用户问题生成默认会话标题。
     *
     * @param question 用户问题
     * @return 最多 18 字符的标题
     */
    private static String titleFromQuestion(String question) {
        String normalized = compact(question);
        if (normalized.isBlank()) {
            return "新对话";
        }
        return normalized.length() <= 18 ? normalized : normalized.substring(0, 18) + "...";
    }
}
