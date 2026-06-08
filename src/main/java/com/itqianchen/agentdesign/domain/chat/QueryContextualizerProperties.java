package com.itqianchen.agentdesign.domain.chat;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Query Contextualizer 配置属性 映射 聊天会话 的 YAML 配置。
 * <p>通过类型化配置隔离环境变量、默认值和业务代码。</p>
 */
@ConfigurationProperties(prefix = "app.chat.query-contextualizer")
public record QueryContextualizerProperties(
        Boolean enabled,
        String mode,
        int maxHistoryMessages,
        int maxRewrittenQueryLength
) {

    private static final int DEFAULT_MAX_HISTORY_MESSAGES = 6;
    private static final int DEFAULT_MAX_REWRITTEN_QUERY_LENGTH = 800;

    /**
     * 解析 resolved Enabled 的最终取值。
     * <p>默认值、兼容规则和异常输入兜底集中在这里。</p>
     */
    public boolean resolvedEnabled() {
        return enabled == null || enabled;
    }

    /**
     * 解析追问补全的配置兜底模式。
     * <p>新环境变量 {@code COGNINOTE_QUERY_CONTEXTUALIZER_MODE} 优先；旧开关显式为 false 时兼容为 OFF。</p>
     */
    public QueryContextualizerMode resolvedMode() {
        if (mode != null && !mode.isBlank()) {
            return QueryContextualizerMode.fromConfig(mode);
        }
        if (enabled != null && !enabled) {
            return QueryContextualizerMode.OFF;
        }
        return QueryContextualizerMode.AUTO;
    }

    /**
     * 解析 resolved Max History Messages 的最终取值。
     * <p>默认值、兼容规则和异常输入兜底集中在这里。</p>
     */
    public int resolvedMaxHistoryMessages() {
        return Math.max(0, maxHistoryMessages > 0 ? maxHistoryMessages : DEFAULT_MAX_HISTORY_MESSAGES);
    }

    /**
     * 解析 resolved Max Rewritten Query Length 的最终取值。
     * <p>默认值、兼容规则和异常输入兜底集中在这里。</p>
     */
    public int resolvedMaxRewrittenQueryLength() {
        return Math.max(100, maxRewrittenQueryLength > 0
                ? maxRewrittenQueryLength
                : DEFAULT_MAX_REWRITTEN_QUERY_LENGTH);
    }
}
