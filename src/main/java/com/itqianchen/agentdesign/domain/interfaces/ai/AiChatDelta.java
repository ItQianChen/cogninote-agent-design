package com.itqianchen.agentdesign.domain.interfaces.ai;

/**
 * Chat runtime 的结构化流式片段。
 *
 * <p>推理内容只用于展示和持久化，不能并入最终回答文本。</p>
 */
public record AiChatDelta(String text, String reasoning) {
    public static AiChatDelta text(String text) {
        return new AiChatDelta(text, null);
    }
}
