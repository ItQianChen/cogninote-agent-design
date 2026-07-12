package com.itqianchen.agentdesign.ocr;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.properties.ocr.OcrPromptProperties;
import org.junit.jupiter.api.Test;

class OcrPromptPropertiesTests {

    @Test
    void rejectsBlankPrompt() {
        assertThatThrownBy(() -> new OcrPromptProperties("system", " ", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.ocr.prompts.page");
    }
}
