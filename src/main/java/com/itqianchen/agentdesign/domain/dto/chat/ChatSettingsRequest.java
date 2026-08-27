package com.itqianchen.agentdesign.domain.dto.chat;

import com.itqianchen.agentdesign.domain.enums.chat.QueryContextualizerMode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 聊天设置请求。
 * <p>字段校验应和前端设置页、接口文档保持一致。</p>
 */
public record ChatSettingsRequest(
        @NotNull QueryContextualizerMode queryContextualizerMode,
        @Min(50) @Max(100) Integer assistantMessageWidth,
        @Min(50) @Max(100) Integer userMessageWidth,
        @Min(50) @Max(100) Integer composerWidth
) {

    /**
     * 保留旧的服务调用构造方式，未传宽度时由设置服务读取数据库中的当前值。
     */
    public ChatSettingsRequest(QueryContextualizerMode queryContextualizerMode) {
        this(queryContextualizerMode, null, null, null);
    }

    /**
     * 兼容前一版本已包含两个消息宽度字段的 Java 调用方。
     */
    public ChatSettingsRequest(
            QueryContextualizerMode queryContextualizerMode,
            Integer assistantMessageWidth,
            Integer userMessageWidth
    ) {
        this(queryContextualizerMode, assistantMessageWidth, userMessageWidth, null);
    }
}
