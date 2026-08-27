package com.itqianchen.agentdesign.domain.dto.chat;

import com.itqianchen.agentdesign.domain.enums.chat.QueryContextualizerMode;

/**
 * 聊天设置响应。
 * <p>宽度字段属于 UI 偏好，但与追问策略一起由本地 SQLite 持久化。</p>
 */
public record ChatSettingsResponse(
        QueryContextualizerMode queryContextualizerMode,
        int assistantMessageWidth,
        int userMessageWidth,
        int composerWidth
) {

    /**
     * 兼容前一版本只读取两个消息宽度字段的 Java 调用方。
     */
    public ChatSettingsResponse(
            QueryContextualizerMode queryContextualizerMode,
            int assistantMessageWidth,
            int userMessageWidth
    ) {
        this(queryContextualizerMode, assistantMessageWidth, userMessageWidth, 100);
    }
}
