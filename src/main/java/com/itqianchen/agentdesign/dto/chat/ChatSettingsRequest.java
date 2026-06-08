package com.itqianchen.agentdesign.dto.chat;

import com.itqianchen.agentdesign.domain.chat.QueryContextualizerMode;
import jakarta.validation.constraints.NotNull;

/**
 * 聊天设置请求。
 * <p>字段校验应和前端设置页、接口文档保持一致。</p>
 */
public record ChatSettingsRequest(
        @NotNull QueryContextualizerMode queryContextualizerMode
) {
}
