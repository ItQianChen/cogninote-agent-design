package com.itqianchen.agentdesign.common.api;

/**
 * Api Error Code 枚举 通用 API 的稳定取值。
 * <p>枚举值可能进入数据库或 API 响应，修改时需要考虑兼容性。</p>
 */
public enum ApiErrorCode {
    OK,
    BAD_REQUEST,
    UNAUTHORIZED,
    VALIDATION_ERROR,
    NOT_FOUND,
    EMBEDDING_UNAVAILABLE,
    MODEL_CONFIGURATION,
    SEARCH_INDEX_ERROR,
    INTERNAL_ERROR
}
