package com.itqianchen.agentdesign.domain.dto.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR 设置响应。
 *
 * <p>该响应会按用户本机设置中心诉求回显 apiKey/secretKey 明文；其他接口不得复用该 DTO。</p>
 */
public record OcrSettingsResponse(
        boolean enabled,
        OcrProvider provider,
        boolean available,
        BaiduSettings baidu,
        Limits limits
) {

    public record BaiduSettings(
            String apiKey,
            String secretKey,
            boolean apiKeyConfigured,
            boolean secretKeyConfigured,
            BaiduOcrRecognitionMode recognitionMode,
            String languageType,
            boolean detectDirection
    ) {
    }

    public record Limits(
            int maxPagesPerDocument,
            int timeoutPerPageSeconds,
            int monthlyCallBudget
    ) {
    }
}
