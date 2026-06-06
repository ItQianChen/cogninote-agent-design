package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.dto.chat.ChatStreamRequest;
import org.springframework.stereotype.Service;

@Service
public class AgentExecutionService {

    private final ChatAgentRouter chatAgentRouter;

    public AgentExecutionService(ChatAgentRouter chatAgentRouter) {
        this.chatAgentRouter = chatAgentRouter;
    }

    public AgentChatStream stream(ChatStreamRequest request) {
        AgentRequest agentRequest = AgentRequest.from(request);
        return chatAgentRouter.stream(agentRequest);
    }
}
