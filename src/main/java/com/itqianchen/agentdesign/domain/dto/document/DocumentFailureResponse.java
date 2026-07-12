package com.itqianchen.agentdesign.domain.dto.document;

import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;

/**
 * 文档处理失败诊断响应。
 *
 * <p>message 面向普通用户，detail 只包含脱敏后的技术信息。API Key、请求体、图片数据和堆栈
 * 不得进入该结构。</p>
 */
public record DocumentFailureResponse(
        String sourcePath,
        String stage,
        String code,
        String message,
        String detail,
        String suggestion,
        Long occurredAt,
        Integer pageNumber,
        String provider,
        String modelName,
        Integer httpStatus,
        String providerErrorCode,
        Integer completedPages,
        Integer totalPages,
        Integer resumePage
) {
    public DocumentFailureResponse(
            String sourcePath,
            String stage,
            String code,
            String message,
            String detail,
            String suggestion,
            Long occurredAt,
            Integer pageNumber,
            String provider,
            String modelName,
            Integer httpStatus,
            String providerErrorCode
    ) {
        this(sourcePath, stage, code, message, detail, suggestion, occurredAt, pageNumber,
                provider, modelName, httpStatus, providerErrorCode, null, null, null);
    }

    public static DocumentFailureResponse of(
            String sourcePath,
            DocumentFailureStage stage,
            DocumentFailureCode code,
            String message,
            String detail,
            String suggestion,
            Long occurredAt,
            Integer pageNumber,
            String provider,
            String modelName,
            Integer httpStatus,
            String providerErrorCode
    ) {
        return new DocumentFailureResponse(
                sourcePath,
                stage.name(),
                code.name(),
                message,
                detail,
                suggestion,
                occurredAt,
                pageNumber,
                provider,
                modelName,
                httpStatus,
                providerErrorCode,
                null,
                null,
                null
        );
    }

    public DocumentFailureResponse withOcrProgress(int completedPages, int totalPages, Integer resumePage) {
        return new DocumentFailureResponse(
                sourcePath,
                stage,
                code,
                message,
                detail,
                suggestion,
                occurredAt,
                pageNumber,
                provider,
                modelName,
                httpStatus,
                providerErrorCode,
                completedPages,
                totalPages,
                resumePage
        );
    }

    /**
     * 归一化旧版仅包含 sourcePath/message 的失败记录。
     *
     * @return 字段完整且可直接返回给前端的失败诊断
     */
    public DocumentFailureResponse normalized() {
        String normalizedStage = stage == null || stage.isBlank()
                ? DocumentFailureStage.UNKNOWN.name()
                : stage;
        String normalizedCode = code == null || code.isBlank()
                ? DocumentFailureCode.LEGACY_FAILURE.name()
                : code;
        String normalizedMessage = message == null || message.isBlank() ? "文档处理失败。" : message;
        return new DocumentFailureResponse(
                sourcePath,
                normalizedStage,
                normalizedCode,
                normalizedMessage,
                detail,
                suggestion,
                occurredAt,
                pageNumber,
                provider,
                modelName,
                httpStatus,
                providerErrorCode,
                completedPages,
                totalPages,
                resumePage
        );
    }
}
