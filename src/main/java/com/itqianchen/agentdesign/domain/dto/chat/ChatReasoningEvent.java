package com.itqianchen.agentdesign.domain.dto.chat;

/**
 * 独立于最终回答的推理 SSE 载荷。
 */
public record ChatReasoningEvent(String delta, String status) {
}
