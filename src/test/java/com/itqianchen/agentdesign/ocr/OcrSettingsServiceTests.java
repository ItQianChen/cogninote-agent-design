package com.itqianchen.agentdesign.ocr;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrSettingsRequest;
import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.service.ocr.OcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrEngineRegistry;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsService;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OcrSettingsServiceTests {

    @Test
    void settingsInitializesDefaultDisabledModelVision() {
        Fixture fixture = new Fixture();

        var settings = fixture.service.settings();

        assertThat(settings.enabled()).isFalse();
        assertThat(settings.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(settings.available()).isFalse();
        assertThat(settings.visionModel().modelName()).isEqualTo(ModelConfigDefaults.VISION_MODEL);
        assertThat(settings.visionModel().apiKeyConfigured()).isFalse();
        assertThat(settings.limits().maxPagesPerDocument()).isEqualTo(200);
    }

    @Test
    void updateNormalizesLimitsAndKeepsAvailabilityTiedToVisionModelKey() {
        Fixture fixture = new Fixture();

        var response = fixture.service.update(new OcrSettingsRequest(
                true,
                OcrProvider.MODEL_VISION,
                null,
                new OcrSettingsRequest.Limits(800, 1, 2_000_000)
        ));

        assertThat(response.enabled()).isTrue();
        assertThat(response.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(response.available()).isFalse();
        assertThat(response.limits().maxPagesPerDocument()).isEqualTo(500);
        assertThat(response.limits().timeoutPerPageSeconds()).isEqualTo(3);
        assertThat(response.limits().monthlyCallBudget()).isEqualTo(1_000_000);

        fixture.modelConfigService.setVisionApiKey("sk-vision");
        assertThat(fixture.service.settings().available()).isTrue();
    }

    @Test
    void updateAcceptsTenMinuteTimeoutAndClampsHigherValues() {
        Fixture fixture = new Fixture();

        var maximum = fixture.service.update(new OcrSettingsRequest(
                false,
                OcrProvider.MODEL_VISION,
                null,
                new OcrSettingsRequest.Limits(200, 600, 1000)
        ));
        var aboveMaximum = fixture.service.update(new OcrSettingsRequest(
                false,
                OcrProvider.MODEL_VISION,
                null,
                new OcrSettingsRequest.Limits(200, 601, 1000)
        ));

        assertThat(maximum.limits().timeoutPerPageSeconds()).isEqualTo(600);
        assertThat(aboveMaximum.limits().timeoutPerPageSeconds()).isEqualTo(600);
        assertThat(fixture.service.settings().limits().timeoutPerPageSeconds()).isEqualTo(600);
    }

    @Test
    void oldBaiduSettingsAreMigratedToDisabledModelVision() {
        Fixture fixture = new Fixture();
        fixture.appSettings.save("ocr.settings", """
                {
                  "enabled": true,
                  "provider": "BAIDU_OCR",
                  "apiKey": "baidu-api-key",
                  "secretKey": "baidu-secret-key",
                  "maxPagesPerDocument": 120,
                  "timeoutPerPageSeconds": 30,
                  "monthlyCallBudget": 2000
                }
                """);
        fixture.modelConfigService.setVisionApiKey("sk-vision");

        var settings = fixture.service.settings();

        assertThat(settings.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(settings.enabled()).isFalse();
        assertThat(settings.available()).isFalse();
        assertThat(settings.limits().maxPagesPerDocument()).isEqualTo(120);
    }

    @Test
    void oldBaiduUpdateRequestIsAlsoDisabledAfterMigration() {
        Fixture fixture = new Fixture();
        fixture.modelConfigService.setVisionApiKey("sk-vision");

        var response = fixture.service.update(new OcrSettingsRequest(
                true,
                OcrProvider.BAIDU_OCR,
                null,
                new OcrSettingsRequest.Limits(200, 20, 1000)
        ));

        assertThat(response.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(response.enabled()).isFalse();
        assertThat(response.available()).isFalse();
    }

    @Test
    void testUsesVisionEngineAndDoesNotExposeSecrets() {
        Fixture fixture = new Fixture();
        fixture.modelConfigService.setVisionApiKey("sk-vision");
        fixture.service.update(new OcrSettingsRequest(
                true,
                OcrProvider.MODEL_VISION,
                null,
                new OcrSettingsRequest.Limits(200, 20, 1000)
        ));

        OcrTestResponse response = fixture.service.test();

        assertThat(response.success()).isTrue();
        assertThat(response.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(response.modelName()).isEqualTo(ModelConfigDefaults.VISION_MODEL);
        assertThat(response.message()).doesNotContain("sk-vision");
        assertThat(fixture.ocrEngine.testCalls).isEqualTo(1);
    }

    @Test
    void testShortCircuitsWhenVisionModelKeyIsMissing() {
        Fixture fixture = new Fixture();
        fixture.service.update(new OcrSettingsRequest(
                true,
                OcrProvider.MODEL_VISION,
                null,
                null
        ));

        OcrTestResponse response = fixture.service.test();

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("视觉识别模型 API Key");
        assertThat(fixture.ocrEngine.testCalls).isZero();
    }

    private static final class Fixture {

        private final FakeAppSettingRepository appSettings = new FakeAppSettingRepository();
        private final FakeOcrEngine ocrEngine = new FakeOcrEngine();
        private final FakeModelConfigService modelConfigService = new FakeModelConfigService();
        private final OcrSettingsService service = new OcrSettingsService(
                appSettings,
                new ObjectMapper(),
                new OcrEngineRegistry(List.of(ocrEngine)),
                modelConfigService
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

    private static final class FakeModelConfigService extends ModelConfigService {

        private String visionApiKey = "";

        private FakeModelConfigService() {
            super(null);
        }

        @Override
        public ModelConfig activeVisionOrDefault() {
            return visionConfig(visionApiKey);
        }

        private void setVisionApiKey(String visionApiKey) {
            this.visionApiKey = visionApiKey;
        }

        private static ModelConfig visionConfig(String apiKey) {
            long now = System.currentTimeMillis();
            return new ModelConfig(
                    ModelConfigDefaults.ACTIVE_VISION_CONFIG_ID,
                    ModelConfigRole.VISION,
                    ModelProvider.OPENAI_COMPATIBLE,
                    ModelConfigDefaults.VISION_DISPLAY_NAME,
                    ModelConfigDefaults.BASE_URL,
                    apiKey,
                    ModelConfigDefaults.VISION_MODEL,
                    null,
                    null,
                    null,
                    null,
                    ModelConfigDefaults.VISION_TEMPERATURE,
                    null,
                    null,
                    true,
                    now,
                    now
            );
        }
    }

    private static final class FakeOcrEngine implements OcrEngine {

        private int testCalls;

        @Override
        public boolean supports(OcrProvider provider) {
            return provider == OcrProvider.MODEL_VISION;
        }

        @Override
        public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
            return "recognized text";
        }

        @Override
        public OcrTestResponse test(OcrSettingsSnapshot settings) {
            testCalls++;
            return new OcrTestResponse(true, "视觉模型测试通过。", settings.provider(), ModelConfigDefaults.VISION_MODEL);
        }
    }
}
