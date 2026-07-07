package com.itqianchen.agentdesign.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsRequest;
import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import com.itqianchen.agentdesign.service.ocr.OcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrEngineRegistry;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsService;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OcrSettingsServiceTests {

    @Test
    void settingsInitializesDefaultDisabledBaiduOcr() {
        OcrSettingsService service = service();

        var settings = service.settings();

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.provider()).isEqualTo(OcrProvider.BAIDU_OCR);
        assertThat(settings.baidu().apiKey()).isBlank();
        assertThat(settings.baidu().secretKey()).isBlank();
        assertThat(settings.limits().maxPagesPerDocument()).isEqualTo(200);
    }

    @Test
    void updateReturnsPlaintextKeysAndEnablesWhenCredentialsExist() {
        OcrSettingsService service = service();

        var response = service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings(
                        " api-key ",
                        " secret-key ",
                        BaiduOcrRecognitionMode.ACCURATE,
                        "eng",
                        false
                ),
                new OcrSettingsRequest.Limits(250, 30, 2000)
        ));

        assertThat(response.enabled()).isTrue();
        assertThat(response.available()).isTrue();
        assertThat(response.baidu().apiKey()).isEqualTo("api-key");
        assertThat(response.baidu().secretKey()).isEqualTo("secret-key");
        assertThat(response.baidu().recognitionMode()).isEqualTo(BaiduOcrRecognitionMode.ACCURATE);
        assertThat(response.baidu().languageType()).isEqualTo("ENG");
        assertThat(response.baidu().detectDirection()).isFalse();
    }

    @Test
    void clearCredentialsDisablesOcr() {
        OcrSettingsService service = service();
        service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings("api-key", "secret-key",
                        BaiduOcrRecognitionMode.STANDARD, "CHN_ENG", true),
                new OcrSettingsRequest.Limits(200, 20, 1000)
        ));

        var response = service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings("", "",
                        BaiduOcrRecognitionMode.STANDARD, "CHN_ENG", true),
                null
        ));

        assertThat(response.enabled()).isFalse();
        assertThat(response.available()).isFalse();
        assertThat(response.baidu().apiKeyConfigured()).isFalse();
        assertThat(response.baidu().secretKeyConfigured()).isFalse();
    }

    @Test
    void partialUpdatePreservesKeysWhenKeyFieldsAreNull() {
        OcrSettingsService service = service();
        service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings("api-key", "secret-key",
                        BaiduOcrRecognitionMode.STANDARD, "CHN_ENG", true),
                null
        ));

        var response = service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings(null, null,
                        BaiduOcrRecognitionMode.ACCURATE, null, null),
                null
        ));

        assertThat(response.baidu().apiKey()).isEqualTo("api-key");
        assertThat(response.baidu().secretKey()).isEqualTo("secret-key");
        assertThat(response.baidu().recognitionMode()).isEqualTo(BaiduOcrRecognitionMode.ACCURATE);
    }

    @Test
    void testResponseDoesNotReturnSecrets() {
        OcrSettingsService service = service();
        service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                new OcrSettingsRequest.BaiduSettings("api-key", "secret-key",
                        BaiduOcrRecognitionMode.STANDARD, "CHN_ENG", true),
                null
        ));

        OcrTestResponse response = service.test();

        assertThat(response.success()).isTrue();
        assertThat(response.message()).doesNotContain("api-key", "secret-key");
    }

    private static OcrSettingsService service() {
        return new OcrSettingsService(
                new FakeAppSettingRepository(),
                new ObjectMapper(),
                new OcrEngineRegistry(List.of(new FakeOcrEngine()))
        );
    }

    private static final class FakeAppSettingRepository extends AppSettingRepository {

        private final Map<String, String> values = new HashMap<>();

        private FakeAppSettingRepository() {
            super(null);
        }

        @Override
        public Optional<String> findValue(String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void save(String key, String value) {
            values.put(key, value);
        }
    }

    private static final class FakeOcrEngine implements OcrEngine {

        @Override
        public boolean supports(OcrProvider provider) {
            return provider == OcrProvider.BAIDU_OCR;
        }

        @Override
        public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
            return "recognized text";
        }

        @Override
        public OcrTestResponse test(OcrSettingsSnapshot settings) {
            return new OcrTestResponse(true, "OCR 测试通过。", settings.provider(), settings.recognitionMode());
        }
    }
}
