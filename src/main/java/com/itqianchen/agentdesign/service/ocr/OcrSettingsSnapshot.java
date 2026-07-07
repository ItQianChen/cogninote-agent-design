package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR 运行时设置快照。
 *
 * <p>该对象会在 PDF 解析流程中读取，包含明文密钥；不要放进日志或异常消息。</p>
 */
public record OcrSettingsSnapshot(
        boolean enabled,
        OcrProvider provider,
        String apiKey,
        String secretKey,
        BaiduOcrRecognitionMode recognitionMode,
        String languageType,
        boolean detectDirection,
        int maxPagesPerDocument,
        int timeoutPerPageSeconds,
        int monthlyCallBudget
) {

    public static OcrSettingsSnapshot defaults() {
        return new OcrSettingsSnapshot(
                false,
                OcrProvider.BAIDU_OCR,
                "",
                "",
                BaiduOcrRecognitionMode.STANDARD,
                "CHN_ENG",
                true,
                200,
                20,
                1000
        );
    }

    public boolean credentialsConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }

    public boolean available() {
        return enabled && credentialsConfigured();
    }
}
