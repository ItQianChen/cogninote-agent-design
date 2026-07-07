package com.itqianchen.agentdesign.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.enums.ocr.BaiduOcrRecognitionMode;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.service.ocr.BaiduAccessToken;
import com.itqianchen.agentdesign.service.ocr.BaiduOcrClient;
import com.itqianchen.agentdesign.service.ocr.BaiduOcrEngine;
import com.itqianchen.agentdesign.service.ocr.BaiduOcrRecognitionRequest;
import com.itqianchen.agentdesign.service.ocr.BaiduOcrRecognitionResponse;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrProviderException;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BaiduOcrEngineTests {

    @Test
    void recognizeUsesTokenAndStandardModeRequest() {
        FakeBaiduOcrClient client = new FakeBaiduOcrClient();
        BaiduOcrEngine engine = new BaiduOcrEngine(client);

        String text = engine.recognize(pageImage(), settings(BaiduOcrRecognitionMode.STANDARD));

        assertThat(text).isEqualTo("alpha\nbeta");
        assertThat(client.tokenCalls).isEqualTo(1);
        assertThat(client.lastRequest.recognitionMode()).isEqualTo(BaiduOcrRecognitionMode.STANDARD);
        assertThat(client.lastRequest.languageType()).isEqualTo("CHN_ENG");
        assertThat(client.lastRequest.imageBase64()).isNotBlank();
    }

    @Test
    void recognizeUsesAccurateModeWhenConfigured() {
        FakeBaiduOcrClient client = new FakeBaiduOcrClient();
        BaiduOcrEngine engine = new BaiduOcrEngine(client);

        engine.recognize(pageImage(), settings(BaiduOcrRecognitionMode.ACCURATE));

        assertThat(client.lastRequest.recognitionMode()).isEqualTo(BaiduOcrRecognitionMode.ACCURATE);
    }

    @Test
    void accessTokenIsCachedForSameCredentials() {
        FakeBaiduOcrClient client = new FakeBaiduOcrClient();
        BaiduOcrEngine engine = new BaiduOcrEngine(client);
        OcrSettingsSnapshot settings = settings(BaiduOcrRecognitionMode.STANDARD);

        engine.recognize(pageImage(), settings);
        engine.recognize(pageImage(), settings);

        assertThat(client.tokenCalls).isEqualTo(1);
        assertThat(client.recognitionCalls).isEqualTo(2);
    }

    @Test
    void quotaErrorMessageIsSanitized() {
        FakeBaiduOcrClient client = new FakeBaiduOcrClient();
        client.recognitionResponse = new BaiduOcrRecognitionResponse(17, "Open api daily request limit reached",
                List.of());
        BaiduOcrEngine engine = new BaiduOcrEngine(client);

        assertThatThrownBy(() -> engine.recognize(pageImage(), settings(BaiduOcrRecognitionMode.STANDARD)))
                .isInstanceOf(OcrProviderException.class)
                .hasMessageContaining("额度")
                .hasMessageNotContaining("api-key")
                .hasMessageNotContaining("secret-key")
                .hasMessageNotContaining("access-token");
    }

    @Test
    void emptyResultReturnsBlankText() {
        FakeBaiduOcrClient client = new FakeBaiduOcrClient();
        client.recognitionResponse = new BaiduOcrRecognitionResponse(null, null, List.of());
        BaiduOcrEngine engine = new BaiduOcrEngine(client);

        assertThat(engine.recognize(pageImage(), settings(BaiduOcrRecognitionMode.STANDARD))).isBlank();
    }

    @Test
    void testResponseDoesNotExposeSecrets() {
        BaiduOcrEngine engine = new BaiduOcrEngine(new FakeBaiduOcrClient());

        var response = engine.test(settings(BaiduOcrRecognitionMode.STANDARD));

        assertThat(response.success()).isTrue();
        assertThat(response.message()).doesNotContain("api-key", "secret-key", "access-token");
    }

    private static OcrPageImage pageImage() {
        return new OcrPageImage(Path.of("sample.pdf"), 1, "image-bytes".getBytes(StandardCharsets.UTF_8));
    }

    private static OcrSettingsSnapshot settings(BaiduOcrRecognitionMode mode) {
        return new OcrSettingsSnapshot(
                true,
                OcrProvider.BAIDU_OCR,
                "api-key",
                "secret-key",
                mode,
                "CHN_ENG",
                true,
                200,
                20,
                1000
        );
    }

    private static final class FakeBaiduOcrClient implements BaiduOcrClient {

        private int tokenCalls;
        private int recognitionCalls;
        private BaiduOcrRecognitionRequest lastRequest;
        private BaiduOcrRecognitionResponse recognitionResponse =
                new BaiduOcrRecognitionResponse(null, null, List.of("alpha", "beta"));

        @Override
        public BaiduAccessToken fetchAccessToken(String apiKey, String secretKey, int timeoutSeconds) {
            tokenCalls++;
            return new BaiduAccessToken("access-token", 3600);
        }

        @Override
        public BaiduOcrRecognitionResponse recognizeImage(BaiduOcrRecognitionRequest request) {
            recognitionCalls++;
            lastRequest = request;
            return recognitionResponse;
        }
    }
}
