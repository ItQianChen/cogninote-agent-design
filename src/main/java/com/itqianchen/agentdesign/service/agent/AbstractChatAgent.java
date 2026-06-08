package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.dto.chat.ChatContextUsageResponse;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import reactor.core.publisher.Flux;

public abstract class AbstractChatAgent implements ChatAgent {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final ModelConfigService modelConfigService;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final PromptAssembler promptAssembler;
    private final ChatSessionService chatSessionService;

    protected AbstractChatAgent(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            PromptAssembler promptAssembler,
            ChatSessionService chatSessionService
    ) {
        this.modelConfigService = modelConfigService;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.promptAssembler = promptAssembler;
        this.chatSessionService = chatSessionService;
    }

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
                topK
        );
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
        ChatContextUsageResponse initialContextUsage = chatSessionService.contextUsage(conversationId);
        Map<String, Object> advisorParams = Map.of(
                ChatMemory.CONVERSATION_ID, conversationId,
                CogninoteMemoryAdvisor.MAX_MESSAGE_SEQUENCE, userMessage.sequence() - 1,
                CogninoteMemoryAdvisor.AGENT_TYPE, type()
        );
        StringBuilder assistantAnswer = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);
        Flux<String> answer = Flux.defer(() -> aiRuntimeFactory.chatRuntime(chatConfig)
                .stream(
                        promptAssembler.systemPrompt(type()),
                        promptAssembler.userPrompt(type(), question),
                        invocation.advisors(),
                        advisorParams
                )
                .doOnNext(assistantAnswer::append)
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
                        requestId,
                        knowledgeContext
                ))
                .doOnError(error -> saveAssistantError(
                        saved,
                        conversationId,
                        assistantAnswer.toString(),
                        requestId,
                        knowledgeContext
                )));

        return new AgentChatStream(
                requestId,
                conversationId,
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources(),
                initialContextUsage,
                () -> chatSessionService.contextUsage(conversationId),
                answer,
                () -> saveAssistantStopped(
                        saved,
                        conversationId,
                        assistantAnswer.toString(),
                        requestId,
                        knowledgeContext
                )
        );
    }

    protected abstract AgentInvocation prepareInvocation(AgentInvocationRequest request);

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

    private void saveAssistantDone(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String requestId,
            KnowledgeContext knowledgeContext
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantDone(
                conversationId,
                content,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources()
        );
    }

    private void saveAssistantStopped(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String requestId,
            KnowledgeContext knowledgeContext
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantStopped(
                conversationId,
                content,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources()
        );
    }

    private void saveAssistantError(
            AtomicBoolean saved,
            String conversationId,
            String content,
            String requestId,
            KnowledgeContext knowledgeContext
    ) {
        if (content.isBlank() || !saved.compareAndSet(false, true)) {
            return;
        }
        chatSessionService.appendAssistantError(
                conversationId,
                content,
                requestId,
                type(),
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources()
        );
    }

    private static int normalizeTopK(Integer requestedTopK, int configuredTopK) {
        int value = requestedTopK == null ? configuredTopK : requestedTopK;
        return Math.clamp(value, 1, 50);
    }
}
