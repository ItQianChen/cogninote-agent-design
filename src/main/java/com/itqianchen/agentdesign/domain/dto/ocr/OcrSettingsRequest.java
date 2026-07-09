package com.itqianchen.agentdesign.domain.dto.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * OCR 设置保存请求。
 *
 * <p>OCR 只保存开关和运行限制；实际密钥归属独立的 VISION 模型配置。</p>
 */
public record OcrSettingsRequest(
        Boolean enabled,
        OcrProvider engine,
        OcrProvider provider,
        @Valid Limits limits
) {

    public record Limits(
            @Min(1) @Max(500) Integer maxPagesPerDocument,
            @Min(3) @Max(120) Integer timeoutPerPageSeconds,
            @Min(1) @Max(1000000) Integer monthlyCallBudget
    ) {
    }
}
