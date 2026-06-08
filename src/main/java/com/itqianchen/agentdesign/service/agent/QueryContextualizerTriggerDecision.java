package com.itqianchen.agentdesign.service.agent;

/**
 * 追问补全触发判断结果。
 * <p>记录触发原因和分数，便于日志观察 AUTO 模式为什么调用或跳过补全 Agent。</p>
 */
public record QueryContextualizerTriggerDecision(
        boolean shouldInvoke,
        String reason,
        int score
) {
}
