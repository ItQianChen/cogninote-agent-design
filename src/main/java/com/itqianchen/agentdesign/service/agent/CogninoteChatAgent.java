package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.chat.ChatMessage;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class CogninoteChatAgent {

    private static final Logger log = LoggerFactory.getLogger(CogninoteChatAgent.class);

    private final ModelConfigService modelConfigService;
    private final AiRuntimeFactory aiRuntimeFactory;
    private final KnowledgeContextProvider knowledgeContextProvider;
    private final PromptAssembler promptAssembler;
    private final ChatSessionService chatSessionService;
    private final CogninoteMemoryAdvisor memoryAdvisor;

    public CogninoteChatAgent(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            KnowledgeContextProvider knowledgeContextProvider,
            PromptAssembler promptAssembler,
            ChatSessionService chatSessionService,
            CogninoteMemoryAdvisor memoryAdvisor
    ) {
        this.modelConfigService = modelConfigService;
        this.aiRuntimeFactory = aiRuntimeFactory;
        this.knowledgeContextProvider = knowledgeContextProvider;
        this.promptAssembler = promptAssembler;
        this.chatSessionService = chatSessionService;
        this.memoryAdvisor = memoryAdvisor;
    }

    public AgentChatStream stream(AgentRequest request) {
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
        boolean useKnowledgeBase = request.useKnowledgeBase();

        chatSessionService.ensureSession(
                conversationId,
                question,
                useKnowledgeBase,
                requestedMode,
                topK
        );
        ChatMessage userMessage = chatSessionService.appendUserMessage(
                conversationId,
                question,
                requestId,
                useKnowledgeBase,
                requestedMode,
                topK
        );
        CogninoteDocumentRetriever documentRetriever = useKnowledgeBase
                ? new CogninoteDocumentRetriever(knowledgeContextProvider, question, requestedMode, topK)
                : null;
        KnowledgeContext knowledgeContext = documentRetriever == null
                ? new KnowledgeContext(null, List.of())
                : documentRetriever.retrieveKnowledgeContext();

        List<Advisor> advisors = advisors(knowledgeContext, documentRetriever);
        Map<String, Object> advisorParams = Map.of(
                ChatMemory.CONVERSATION_ID, conversationId,
                CogninoteMemoryAdvisor.MAX_MESSAGE_SEQUENCE, userMessage.sequence() - 1
        );
        StringBuilder assistantAnswer = new StringBuilder();
        AtomicBoolean saved = new AtomicBoolean(false);
        Flux<String> answer = Flux.defer(() -> {
            return aiRuntimeFactory.chatRuntime(chatConfig)
                    .stream(
                            promptAssembler.systemPrompt(),
                            promptAssembler.userPrompt(question),
                            advisors,
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
                            "agent_chat_failed requestId={} conversationId={} provider={} modelName={} retrievalMode={} topK={} sourceCount={} durationMs={}",
                            requestId,
                            conversationId,
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
                    ));
        });

        return new AgentChatStream(
                requestId,
                conversationId,
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources(),
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

    private List<Advisor> advisors(KnowledgeContext knowledgeContext, CogninoteDocumentRetriever documentRetriever) {
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(memoryAdvisor);
        if (knowledgeContext.retrievalMode() != null && documentRetriever != null) {
            advisors.add(RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
                    .queryAugmenter(new CogninoteRagQueryAugmenter(promptAssembler.emptyContextPrompt()))
                    .build());
        }
        return advisors;
    }

    private static void logAgentCompleted(
            String requestId,
            String conversationId,
            ModelConfig chatConfig,
            KnowledgeContext knowledgeContext,
            int topK,
            long startedAt
    ) {
        log.info(
                "agent_chat_completed requestId={} conversationId={} provider={} modelName={} retrievalMode={} topK={} sourceCount={} durationMs={}",
                requestId,
                conversationId,
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
                knowledgeContext.retrievalMode(),
                knowledgeContext.sources()
        );
    }

    private static int normalizeTopK(Integer requestedTopK, int configuredTopK) {
        int value = requestedTopK == null ? configuredTopK : requestedTopK;
        return Math.clamp(value, 1, 50);
    }
}
