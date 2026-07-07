package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;

/**
 * OCR Provider 调用失败。
 *
 * <p>消息会进入导入失败列表和维护记录，必须保持脱敏。</p>
 */
public class OcrProviderException extends DocumentParseException {

    public OcrProviderException(String message) {
        super(message);
    }
}
