package com.itqianchen.agentdesign.domain.dto.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR Provider 连通性测试响应。
 *
 * <p>测试结果只暴露 Provider、模式和可读消息，不返回任何密钥或 access_token。</p>
 */
public record OcrTestResponse(
        boolean success,
        String message,
        OcrProvider provider,
        BaiduOcrRecognitionMode mode
) {
}
