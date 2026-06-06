package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeBaseChatAgent extends AbstractChatAgent {

    private final KnowledgeContextProvider knowledgeContextProvider;
    private final PromptAssembler promptAssembler;
    private final CogninoteMemoryAdvisor memoryAdvisor;

    public KnowledgeBaseChatAgent(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            KnowledgeContextProvider knowledgeContextProvider,
            PromptAssembler promptAssembler,
            ChatSessionService chatSessionService,
            CogninoteMemoryAdvisor memoryAdvisor
    ) {
        super(modelConfigService, aiRuntimeFactory, promptAssembler, chatSessionService);
        this.knowledgeContextProvider = knowledgeContextProvider;
        this.promptAssembler = promptAssembler;
        this.memoryAdvisor = memoryAdvisor;
    }

    @Override
    public AgentType type() {
        return AgentType.KNOWLEDGE_BASE;
    }

    @Override
    public boolean supports(AgentRequest request) {
        return request.useKnowledgeBase();
    }

    @Override
    protected AgentInvocation prepareInvocation(String question, SearchMode requestedMode, int topK) {
        CogninoteDocumentRetriever documentRetriever = new CogninoteDocumentRetriever(
                knowledgeContextProvider,
                question,
                requestedMode,
                topK
        );
        KnowledgeContext knowledgeContext = documentRetriever.retrieveKnowledgeContext();
        List<Advisor> advisors = new ArrayList<>();
        advisors.add(memoryAdvisor);
        if (knowledgeContext.retrievalMode() != null) {
            advisors.add(RetrievalAugmentationAdvisor.builder()
                    .documentRetriever(documentRetriever)
                    .queryAugmenter(new CogninoteRagQueryAugmenter(promptAssembler.emptyContextPrompt()))
                    .build());
        }
        return new AgentInvocation(knowledgeContext, advisors);
    }
}
