package com.itqianchen.agentdesign.domain.dto.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR 设置响应。
 *
 * <p>密钥归属模型配置接口，本响应只暴露视觉模型摘要和 OCR 运行限制。</p>
 */
public record OcrSettingsResponse(
        boolean enabled,
        OcrProvider engine,
        boolean available,
        VisionModelSettings visionModel,
        Limits limits
) {

    public record VisionModelSettings(
            String id,
            String provider,
            String displayName,
            String baseUrl,
            boolean apiKeyConfigured,
            String modelName
    ) {
    }

    public record Limits(
            int maxPagesPerDocument,
            int timeoutPerPageSeconds,
            int monthlyCallBudget
    ) {
    }
}
