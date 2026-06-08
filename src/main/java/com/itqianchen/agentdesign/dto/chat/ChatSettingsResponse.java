package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.chat.QueryContextualizerMode;

/**
 * 聊天设置响应。
 * <p>当前只暴露追问补全策略；后续聊天级设置可在此扩展。</p>
 */
public record ChatSettingsResponse(
        QueryContextualizerMode queryContextualizerMode
) {
}
