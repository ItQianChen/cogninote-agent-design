package com.itqianchen.agentdesign.service.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

class DefaultBaiduOcrClientTests {

    @Test
    void formIncludesLanguageTypeForStandardMode() {
        MultiValueMap<String, String> form = DefaultBaiduOcrClient.formFor(request(BaiduOcrRecognitionMode.STANDARD));

        assertThat(form.getFirst("image")).isEqualTo("base64-image");
        assertThat(form.getFirst("detect_direction")).isEqualTo("true");
        assertThat(form.getFirst("language_type")).isEqualTo("ENG");
    }

    @Test
    void formIncludesLanguageTypeForAccurateMode() {
        MultiValueMap<String, String> form = DefaultBaiduOcrClient.formFor(request(BaiduOcrRecognitionMode.ACCURATE));

        assertThat(form.getFirst("language_type")).isEqualTo("ENG");
    }

    private static BaiduOcrRecognitionRequest request(BaiduOcrRecognitionMode mode) {
        return new BaiduOcrRecognitionRequest(
                "access-token",
                "base64-image",
                mode,
                "ENG",
                true,
                20
        );
    }
}
