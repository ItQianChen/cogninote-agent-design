package com.itqianchen.agentdesign.service.ocr;

import java.util.List;

/**
 * 百度 OCR 单页识别结果。
 */
public record BaiduOcrRecognitionResponse(
        Integer errorCode,
        String errorMessage,
        List<String> words
) {

    public boolean hasError() {
        return errorCode != null;
    }
}
