package com.itqianchen.agentdesign.domain.enums.model;

/**
 * Model Capability 枚举 模型配置 的稳定取值。
 * <p>枚举值可能进入数据库或 API 响应，修改时需要考虑兼容性。</p>
 */
public enum ModelCapability {
    CHAT,
    EMBEDDING,
    VISION,
    UNKNOWN
}
