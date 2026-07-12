package com.itqianchen.agentdesign.domain.properties.ocr;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型 OCR Prompt 配置。
 *
 * <p>识别规则统一由 cogninote-prompts.yaml 管理，避免模型行为散落在引擎实现中。</p>
 */
@ConfigurationProperties(prefix = "app.ocr.prompts")
public record OcrPromptProperties(
        String system,
        String page,
        String test
) {

    /** 启动时校验 OCR 模型调用所需的 Prompt 均已配置。 */
    public OcrPromptProperties {
        requireText(system, "app.ocr.prompts.system");
        requireText(page, "app.ocr.prompts.page");
        requireText(test, "app.ocr.prompts.test");
    }

    private static void requireText(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(propertyName + " must not be blank");
        }
    }
}
