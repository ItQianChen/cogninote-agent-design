package com.itqianchen.agentdesign.service.agent;

import com.itqianchen.agentdesign.domain.agent.AgentChatStream;
import com.itqianchen.agentdesign.domain.agent.AgentRequest;
import com.itqianchen.agentdesign.domain.agent.AgentType;

public interface ChatAgent {

    AgentType type();

    boolean supports(AgentRequest request);

    AgentChatStream stream(AgentRequest request);
}
