package com.itqianchen.agentdesign.domain.dto.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * OCR 设置保存请求。
 *
 * <p>apiKey/secretKey 是本机配置，允许前端明文回显；但不得进入日志、测试响应或维护运行记录。</p>
 */
public record OcrSettingsRequest(
        Boolean enabled,
        OcrProvider provider,
        @Valid BaiduSettings baidu,
        @Valid Limits limits
) {

    public record BaiduSettings(
            @Size(max = 2000) String apiKey,
            @Size(max = 2000) String secretKey,
            BaiduOcrRecognitionMode recognitionMode,
            @Size(max = 32) String languageType,
            Boolean detectDirection
    ) {
    }

    public record Limits(
            @Min(1) @Max(500) Integer maxPagesPerDocument,
            @Min(3) @Max(120) Integer timeoutPerPageSeconds,
            @Min(1) @Max(1000000) Integer monthlyCallBudget
    ) {
    }
}
