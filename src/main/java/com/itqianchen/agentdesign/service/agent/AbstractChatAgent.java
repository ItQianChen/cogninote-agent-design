package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.vo.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.vo.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.enums.agent.AgentType;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatDelta;
import com.itqianchen.agentdesign.domain.entity.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.domain.dto.chat.RagSourceResponse;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.service.websearch.ToolExecutionCollector;
import com.itqianchen.agentdesign.service.websearch.WebSearchToolInvocation;
import com.itqianchen.agentdesign.service.websearch.WebSearchToolPolicy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * ChatAgent 的公共流式执行骨架。
 *
 * <p>这里统一处理会话创建、用户消息落库、模型流订阅和助手消息终态保存；
 * 子类只负责准备检索上下文和 advisor。</p>
 */
public abstract class AbstractChatAgent implements ChatAgent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ModelConfigService modelConfigService;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final PromptAssembler promptAssembler;
    private final ChatSessionService chatSessionService;
    private final WebSearchToolPolicy webSearchToolPolicy;

    /**
     * 注入流式执行骨架所需依赖。
     *
     * @param modelConfigService 模型配置服务
     * @param aiRuntimeFactory AI 运行时工厂
     * @param promptAssembler 提示词装配器
     * @param chatSessionService 会话服务
     * @param webSearchToolPolicy 联网搜索工具挂载策略
     */
    protected AbstractChatAgent(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            PromptAssembler promptAssembler,
            ChatSessionService chatSessionService,
            WebSearchToolPolicy webSearchToolPolicy
    ) {
        this.modelConfigService = modelConfigService;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.promptAssembler = promptAssembler;
        this.chatSessionService = chatSessionService;
        this.webSearchToolPolicy = webSearchToolPolicy;
    }

    /**
     * 执行一次可取消的 agent 流，并保证 done/error/stopped 只落库一次。
     *
     * @param request Agent 请求
     * @return 可订阅和取消的聊天流
     */
    @Override
    public final AgentChatStream stream(AgentRequest request) {
        long startedAt = System.currentTimeMillis();
        String requestId = request.requestId() == null || request.requestId().isBlank()
                ? UUID.randomUUID().toString()
                : request.requestId();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();
        ModelConfig chatConfig = modelConfigService.requireActiveChatConfigured();
        String question = request.question().trim();
        int topK = normalizeTopK(request.topK(), chatConfig.resolvedDefaultTopK());
        SearchMode requestedMode = request.mode() == null ? SearchMode.HYBRID : request.mode();
        boolean useKnowledgeBase = type() == AgentType.KNOWLEDGE_BASE;

        chatSessionService.ensureSession(conversationId, question, useKnowledgeBase, requestedMode, topK);
        ChatMessage userMessage = chatSessionService.appendUserMessage(
                conversationId,
                question,
                requestId,
                type(),
                useKnowledgeBase,
                requestedMode,
                topK,
                request.references()
        );
        String modelQuestion = chatSessionService.modelContent(userMessage);
        AgentInvocation invocation = prepareInvocation(new AgentInvocationRequest(
                requestId,
                conversationId,
                question,
                requestedMode,
                topK,
                userMessage,
                chatConfig
        ));
        KnowledgeContext knowledgeContext = invocation.knowledgeContext();
        WebSearchToolInvocation webSearchInvocation = webSearchToolPolicy.prepare(
                request.useWebSearch(),
                requestId,
                knowledgeContext.sources().size()
        );
        ChatContextUsageResponse initialContextUsage = chatSessionService.contextUsage(conversationId);
        Map<String, Object> advisorParams = Map.of(
                ChatMemory.CONVERSATION_ID, conversationId,
                CogninoteMemoryAdvisor.MAX_MESSAGE_SEQUENCE, userMessage.sequence() - 1,
                CogninoteMemoryAdvisor.AGENT_TYPE, type()
        );
        StringBuilder assistantAnswer = new StringBuilder();
        StringBuilder assistantReasoning = new StringBuilder();
        Sinks.Many<String> reasoningSink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicBoolean saved = new AtomicBoolean(false);
        // 模型流是冷流，defer 确保订阅发生后才真正发起外部调用和开始累计答案。
        Flux<String> answer = Flux.defer(() -> aiRuntimeFactory.chatRuntime(chatConfig)
                .streamDeltas(
                        promptAssembler.systemPrompt(type(), webSearchInvocation.enabled()),
                        promptAssembler.userPrompt(type(), modelQuestion),
                        invocation.advisors(),
                        advisorParams,
                        webSearchInvocation.tools(),
                        webSearchInvocation.toolContext()
                )
                .doOnNext(delta -> {
                    if (delta.text() != null) {
                        assistantAnswer.append(delta.text());
                    }
                    if (delta.reasoning() != null) {
                        assistantReasoning.append(delta.reasoning());
                        reasoningSink.tryEmitNext(delta.reasoning());
                    }
                })
                .doFinally(signal -> completeToolEvents(webSearchInvocation))
                .doOnComplete(() -> logAgentCompleted(
                        requestId,
                        conversationId,
                        chatConfig,
                        knowledgeContext,
                        topK,
                        startedAt
                ))
                .doOnError(error -> log.warn(
                        "agent_chat_failed requestId={} conversationId={} agentType={} provider={} modelName={} retrievalMode={} topK={} sourceCount={} durationMs={}",
                        requestId,
                        conversationId,
                        type(),
                        chatConfig.provider(),
                        chatConfig.modelName(),
                        knowledgeContext.retrievalMode(),
                        topK,
                        knowledgeContext.sources().size(),
                        System.currentTimeMillis() - startedAt,
                        error
                ))
                .doOnComplete(() -> saveAssistantDone(
                        saved,
                        conversationId,
                        assistantAnswer.toString(),
                        assistantReasoning.toString(),
                        requestId,
                        knowledgeContext,
                        webSearchInvocation.collector()
                ))
                .doOnError(error -> saveAssistantError(
                        saved,
                        conversationId,
                        assistantAnswer.toString(),
                        assistantReasoning.toString(),
                        requestId,
                        knowledgeContext,
                        webSearchInvocation.collector()
                ))
                .doFinally(signal -> reasoningSink.tryEmitComplete())
                // 推理阶段可能只有 reasoning、没有 text；先过滤整个 delta，再读取正文，避免 Reactor map 返回 null。
                .filter(delta -> delta.text() != null && !delta.text().isEmpty())
                .map(AiChatDelta::text));

        return new AgentChatStream(
                requestId,
                conversationId,
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources(),
                initialContextUsage,
                () -> chatSessionService.contextUsage(conversationId),
                webSearchInvocation.toolEvents(),
                answer,
                reasoningSink.asFlux(),
                () -> saveAssistantStopped(
                        saved,
                        conversationId,
                        assistantAnswer.toString(),
                        assistantReasoning.toString(),
                        requestId,
                        knowledgeContext,
                        webSearchInvocation.collector()
                )
        );
    }

    /**
     * 子类准备检索上下文和 advisor。
     *
     * @param request 已经完成会话和用户消息落库的调用上下文
     * @return 模型调用所需上下文
     */
    protected abstract AgentInvocation prepareInvocation(AgentInvocationRequest request);

    /**
     * 子类准备模型调用所需的稳定上下文。
     *
     * <p>chatConfig 已完成 active/apiKey 校验，子类不需要再次读取模型配置。</p>
     */
    protected record AgentInvocationRequest(
            String requestId,
            String conversationId,
            String question,
            SearchMode requestedMode,
            int topK,
            ChatMessage userMessage,
            ModelConfig chatConfig
    ) {
    }

    protected record AgentInvocation(KnowledgeContext knowledgeContext, List<Advisor> advisors) {
    }

    /**
     * 记录 Agent 正常完成日志。
     *
     * @param requestId 请求 ID
     * @param conversationId 会话 ID
     * @param chatConfig Chat 模型配置
     * @param knowledgeContext 知识库上下文
     * @param topK 检索数量
     * @param startedAt 请求开始时间戳
     */
    private void logAgentCompleted(
            String requestId,
            String conversationId,
            ModelConfig chatConfig,
            KnowledgeContext knowledgeContext,
            int topK,
            long startedAt
    ) {
        log.info(
                "agent_chat_completed requestId={} conversationId={} agentType={} provider={} modelName={} retrievalMode={} topK={} sourceCount={} durationMs={}",
                requestId,
                conversationId,
                type(),
                chatConfig.provider(),
                chatConfig.modelName(),
                knowledgeContext.retrievalMode(),
                topK,
                knowledgeContext.sources().size(),
                System.currentTimeMillis() - startedAt
        );
    }

    /**
     * 保存正常完成的助手回复。
     *
     * @param saved 终态保存幂等标记
     * @param conversationId 会话 ID
     * @param content 助手回复
     * @param requestId 请求 ID
     * @param knowledgeContext 知识库上下文
     */
    private void saveAssistantDone(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            KnowledgeContext knowledgeContext,
            ToolExecutionCollector toolExecutionCollector
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantDone(
                conversationId,
                content,
                reasoningContent,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                allSources(knowledgeContext, toolExecutionCollector)
        );
    }

    /**
     * 保存用户停止时已经生成的助手回复。
     *
     * @param saved 终态保存幂等标记
     * @param conversationId 会话 ID
     * @param content 已生成内容
     * @param requestId 请求 ID
     * @param knowledgeContext 知识库上下文
     */
    private void saveAssistantStopped(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            KnowledgeContext knowledgeContext,
            ToolExecutionCollector toolExecutionCollector
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantStopped(
                conversationId,
                content,
                reasoningContent,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                allSources(knowledgeContext, toolExecutionCollector)
        );
    }

    /**
     * 保存异常结束前已经生成的助手回复。
     *
     * @param saved 终态保存幂等标记
     * @param conversationId 会话 ID
     * @param content 已生成内容
     * @param requestId 请求 ID
     * @param knowledgeContext 知识库上下文
     */
    private void saveAssistantError(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String reasoningContent,
            String requestId,
            KnowledgeContext knowledgeContext,
            ToolExecutionCollector toolExecutionCollector
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantError(
                conversationId,
                content,
                reasoningContent,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                allSources(knowledgeContext, toolExecutionCollector)
        );
    }

    private static void completeToolEvents(WebSearchToolInvocation webSearchInvocation) {
        ToolExecutionCollector collector = webSearchInvocation.collector();
        if (collector != null) {
            collector.complete();
        }
    }

    private static List<RagSourceResponse> allSources(
            KnowledgeContext knowledgeContext,
            ToolExecutionCollector toolExecutionCollector
    ) {
        if (toolExecutionCollector == null || toolExecutionCollector.webSources().isEmpty()) {
            return knowledgeContext.sources();
        }
        return java.util.stream.Stream.concat(
                        knowledgeContext.sources().stream(),
                        toolExecutionCollector.webSources().stream()
                )
                .toList();
    }

    /**
     * 归一化检索数量。
     *
     * @param requestedTopK 请求值
     * @param configuredTopK 模型配置中的默认值
     * @return 限制在 1 到 50 的检索数量
     */
    private static int normalizeTopK(Integer requestedTopK, int configuredTopK) {
        int value = requestedTopK == null ? configuredTopK : requestedTopK;
        return Math.clamp(value, 1, 50);
    }
}
