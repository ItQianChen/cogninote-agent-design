package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR 运行时设置快照。
 *
 * <p>该对象只包含 OCR 开关和调用限制；模型密钥由 VISION 模型配置持有，避免重复状态。</p>
 */
public record OcrSettingsSnapshot(
        boolean enabled,
        OcrProvider provider,
        boolean visionModelConfigured,
        int maxPagesPerDocument,
        int timeoutPerPageSeconds,
        int monthlyCallBudget
) {

    public static OcrSettingsSnapshot defaults() {
        return new OcrSettingsSnapshot(
                false,
                OcrProvider.MODEL_VISION,
                false,
                200,
                20,
                1000
        );
    }

    public boolean available() {
        return enabled && provider == OcrProvider.MODEL_VISION && visionModelConfigured;
    }
}
