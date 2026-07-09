package com.itqianchen.agentdesign.service.ocr;

import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * OCR 引擎注册表。
 *
 * <p>解析流程通过注册表取引擎，后续追加离线或其他云 Provider 不需要改 PDF parser。</p>
 */
@Component
public class OcrEngineRegistry {

    private final List<OcrEngine> engines;

    public OcrEngineRegistry(List<OcrEngine> engines) {
        this.engines = List.copyOf(engines);
    }

    public OcrEngine engineFor(OcrProvider provider) {
        return engines.stream()
                .filter(engine -> engine.supports(provider))
                .findFirst()
                .orElseThrow(() -> new DocumentParseException("OCR Provider is not available: " + provider));
    }
}
