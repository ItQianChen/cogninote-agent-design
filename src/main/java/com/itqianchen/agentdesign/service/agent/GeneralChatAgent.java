package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.search.SearchMode;
import com.itqianchen.agentdesign.service.chat.ChatSessionService;
import com.itqianchen.agentdesign.service.chat.CogninoteMemoryAdvisor;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GeneralChatAgent extends AbstractChatAgent {

    private final CogninoteMemoryAdvisor memoryAdvisor;

    public GeneralChatAgent(
            ModelConfigService modelConfigService,
            AiRuntimeFactory aiRuntimeFactory,
            PromptAssembler promptAssembler,
            ChatSessionService chatSessionService,
            CogninoteMemoryAdvisor memoryAdvisor
    ) {
        super(modelConfigService, aiRuntimeFactory, promptAssembler, chatSessionService);
        this.memoryAdvisor = memoryAdvisor;
    }

    @Override
    public AgentType type() {
        return AgentType.GENERAL_CHAT;
    }

    @Override
    public boolean supports(AgentRequest request) {
        return !request.useKnowledgeBase();
    }

    @Override
    protected AgentInvocation prepareInvocation(String question, SearchMode requestedMode, int topK) {
        return new AgentInvocation(new KnowledgeContext(null, List.of()), List.of(memoryAdvisor));
    }
}
