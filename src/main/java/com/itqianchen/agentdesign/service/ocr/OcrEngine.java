package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;

/**
 * OCR Provider 引擎接口。
 */
public interface OcrEngine {

    boolean supports(OcrProvider provider);

    String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings);

    OcrTestResponse test(OcrSettingsSnapshot settings);
}
