package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;

/**
 * 百度 OCR 单页识别请求。
 *
 * <p>imageBase64 不包含 data URI 前缀；调用层负责按百度接口要求做 form-urlencoded 编码。</p>
 */
public record BaiduOcrRecognitionRequest(
        String accessToken,
        String imageBase64,
        BaiduOcrRecognitionMode recognitionMode,
        String languageType,
        boolean detectDirection,
        int timeoutSeconds
) {
}
