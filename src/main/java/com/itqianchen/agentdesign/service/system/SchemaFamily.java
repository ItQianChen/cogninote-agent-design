package com.itqianchen.agentdesign.service.system;

/** 旧库结构家族；值不绑定应用版本，便于后续版本继续复用适配规则。 */
public enum SchemaFamily {
    EMPTY,
    FLYWAY,
    LEGACY_MODEL_CONFIG,
    LEGACY_CURRENT_TABLES,
    UNKNOWN
}
