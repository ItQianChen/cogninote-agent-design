package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.service.document.DocumentFailureMessages;
import com.itqianchen.agentdesign.service.document.DocumentFailureSanitizer;

/**
 * OCR Provider 调用失败。
 *
 * <p>消息会进入导入失败列表和维护记录，必须保持脱敏。</p>
 */
public class OcrProviderException extends DocumentParseException {

    private final DocumentFailureStage stage;
    private final DocumentFailureCode code;
    private final String detail;
    private final Integer pageNumber;
    private final String provider;
    private final String modelName;
    private final Integer httpStatus;
    private final String providerErrorCode;

    public OcrProviderException(String message) {
        this(message, null);
    }

    public OcrProviderException(String message, Throwable cause) {
        this(
                DocumentFailureStage.MODEL_CALL,
                DocumentFailureCode.MODEL_PROVIDER_FAILED,
                message,
                message,
                null,
                null,
                null,
                null,
                null,
                cause
        );
    }

    public OcrProviderException(
            DocumentFailureStage stage,
            DocumentFailureCode code,
            String message,
            String detail,
            Integer pageNumber,
            String provider,
            String modelName,
            Integer httpStatus,
            String providerErrorCode,
            Throwable cause
    ) {
        super(message, cause);
        this.stage = stage;
        this.code = code;
        this.detail = DocumentFailureSanitizer.sanitize(detail);
        this.pageNumber = pageNumber;
        this.provider = provider;
        this.modelName = modelName;
        this.httpStatus = httpStatus;
        this.providerErrorCode = providerErrorCode;
    }

    public DocumentFailureResponse toFailure(String sourcePath, long occurredAt) {
        return DocumentFailureResponse.of(
                sourcePath,
                stage,
                code,
                getMessage(),
                detail,
                DocumentFailureMessages.suggestion(code),
                occurredAt,
                pageNumber,
                provider,
                modelName,
                httpStatus,
                providerErrorCode
        );
    }

}
