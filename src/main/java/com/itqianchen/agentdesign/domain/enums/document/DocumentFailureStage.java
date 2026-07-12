package com.itqianchen.agentdesign.domain.enums.document;

/**
 * 文档处理失败发生的稳定阶段。
 *
 * <p>阶段用于诊断和展示，不替代 {@link DocumentStatus}；文档是否仍可检索继续由状态字段表达。</p>
 */
public enum DocumentFailureStage {
    SCAN,
    READ,
    PARSE,
    OCR,
    MODEL_CONFIG,
    MODEL_CALL,
    CHUNK,
    PERSIST,
    INDEX,
    UNKNOWN
}
