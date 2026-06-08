package com.itqianchen.agentdesign.domain.chat;

/**
 * 知识库追问补全模式。
 * <p>该模式只控制知识库检索 query 的补全策略，不会改写用户聊天记录中的原始消息。</p>
 */
public enum QueryContextualizerMode {
    /**
     * 自动判断是否需要调用补全 Agent。
     * <p>适合默认使用：完整问题直接检索，省略式追问或弱检索时再补全。</p>
     */
    AUTO,

    /**
     * 始终调用补全 Agent。
     * <p>保留第 21 阶段的行为，准确性更稳但会增加每轮知识库问答的延迟和模型调用成本。</p>
     */
    ALWAYS,

    /**
     * 关闭补全 Agent。
     * <p>始终使用用户原问题检索，成本最低，但省略式追问可能召回不准。</p>
     */
    OFF;

    /**
     * 从配置文本解析补全模式。
     * <p>环境变量可能来自用户手动输入，异常值按默认 {@link #AUTO} 兜底，避免应用启动失败。</p>
     */
    public static QueryContextualizerMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return AUTO;
        }
        try {
            return QueryContextualizerMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return AUTO;
        }
    }
}
