package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR Provider 引擎接口。
 */
public interface OcrEngine {

    boolean supports(OcrProvider provider);

    String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings);

    /**
     * 返回影响 OCR 文本结果的稳定签名。
     *
     * <p>签名不能包含 API Key、超时或调用预算；这些配置变化不应让已完成页面失效。</p>
     */
    default String checkpointSignature(OcrSettingsSnapshot settings) {
        return getClass().getName() + ":v1";
    }

    OcrTestResponse test(OcrSettingsSnapshot settings);
}
