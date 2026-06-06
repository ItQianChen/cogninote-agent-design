package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ChatAgentRouter {

    private final Map<AgentType, ChatAgent> agents;

    public ChatAgentRouter(List<ChatAgent> agents) {
        EnumMap<AgentType, ChatAgent> byType = new EnumMap<>(AgentType.class);
        for (ChatAgent agent : agents) {
            ChatAgent existing = byType.put(agent.type(), agent);
            if (existing != null) {
                throw new IllegalStateException("Duplicate chat agent type: " + agent.type());
            }
        }
        this.agents = Map.copyOf(byType);
    }

    public ChatAgent route(AgentRequest request) {
        AgentType targetType = request.useKnowledgeBase()
                ? AgentType.KNOWLEDGE_BASE
                : AgentType.GENERAL_CHAT;
        ChatAgent agent = agents.get(targetType);
        if (agent == null) {
            throw new IllegalStateException("Chat agent is not registered: " + targetType);
        }
        return agent;
    }

    public AgentChatStream stream(AgentRequest request) {
        return route(request).stream(request);
    }
}
