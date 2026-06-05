package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.chat.ChatPromptProperties;
import org.springframework.stereotype.Service;

@Service
public class PromptAssembler {

    private final ChatPromptProperties promptProperties;

    public PromptAssembler(ChatPromptProperties promptProperties) {
        this.promptProperties = promptProperties;
    }

    public String systemPrompt() {
        return promptProperties.rag().system();
    }

    public String userPrompt(String question) {
        return promptProperties.rag().user()
                .replace("{question}", question);
    }

    public String emptyContextPrompt() {
        return promptProperties.rag().emptyContext();
    }
}
