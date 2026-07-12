package com.itqianchen.agentdesign.domain.exception.ingestion;

import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;

/** 表示 PDF OCR 单页处理中的稳定、可诊断失败。 */
public class OcrPageProcessingException extends DocumentParseException {

    private final DocumentFailureStage stage;
    private final DocumentFailureCode code;
    private final String detail;
    private final Integer pageNumber;
    private final String provider;

    public OcrPageProcessingException(
            DocumentFailureStage stage,
            DocumentFailureCode code,
            String message,
            String detail,
            Integer pageNumber,
            String provider,
            Throwable cause
    ) {
        super(message, cause);
        this.stage = stage;
        this.code = code;
        this.pageNumber = pageNumber;
        this.provider = provider;
        this.detail = detail;
    }

    public DocumentFailureStage stage() {
        return stage;
    }

    public DocumentFailureCode code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    public Integer pageNumber() {
        return pageNumber;
    }

    public String provider() {
        return provider;
    }
}
