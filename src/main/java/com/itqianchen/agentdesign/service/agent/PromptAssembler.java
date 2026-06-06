package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentType;
import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import org.springframework.stereotype.Service;

@Service
public class PromptAssembler {

    private final ChatPromptProperties promptProperties;

    public PromptAssembler(ChatPromptProperties promptProperties) {
        this.promptProperties = promptProperties;
    }

    public String systemPrompt(AgentType agentType) {
        return agentType == AgentType.GENERAL_CHAT
                ? promptProperties.general().system()
                : promptProperties.rag().system();
    }

    public String userPrompt(AgentType agentType, String question) {
        String template = agentType == AgentType.GENERAL_CHAT
                ? promptProperties.general().user()
                : promptProperties.rag().user();
        return template.replace("{question}", question);
    }

    public String emptyContextPrompt() {
        return promptProperties.rag().emptyContext();
    }
}
