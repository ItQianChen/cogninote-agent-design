package com.itqianchen.agentdesign.ocr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiChatRuntime;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiEmbeddingRuntime;
import com.itqianchen.agentdesign.domain.interfaces.ai.AiRuntimeFactory;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.service.ocr.ModelVisionOcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrProviderException;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

class ModelVisionOcrEngineTests {

    @Test
    void supportsOnlyModelVisionProvider() {
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), new FakeRuntimeFactory());

        assertThat(engine.supports(OcrProvider.MODEL_VISION)).isTrue();
        assertThat(engine.supports(OcrProvider.BAIDU_OCR)).isFalse();
    }

    @Test
    void recognizeSendsPngImageToVisionModelAndTrimsResult() {
        FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory();
        runtimeFactory.runtime.nextImageResponse = "  OCR text  ";
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), runtimeFactory);
        byte[] image = new byte[] {1, 2, 3};

        String text = engine.recognize(
                new OcrPageImage(Path.of("page.pdf"), 1, image),
                enabledSettings()
        );

        assertThat(text).isEqualTo("OCR text");
        assertThat(runtimeFactory.lastConfig.role()).isEqualTo(ModelConfigRole.VISION);
        assertThat(runtimeFactory.runtime.lastImageBytes).isSameAs(image);
        assertThat(runtimeFactory.runtime.lastMimeType).isEqualTo(MimeTypeUtils.IMAGE_PNG);
        assertThat(runtimeFactory.runtime.lastSystemPrompt).contains("不要解释", "不要总结", "不要翻译");
        assertThat(runtimeFactory.runtime.lastUserMessage).contains("PDF 页面图片");
    }

    @Test
    void recognizeWrapsProviderFailuresWithSanitizedMessage() {
        FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory();
        runtimeFactory.runtime.failure = new ModelConfigurationException("bad key sk-secret");
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), runtimeFactory);

        assertThatThrownBy(() -> engine.recognize(
                new OcrPageImage(Path.of("page.pdf"), 1, new byte[] {1}),
                enabledSettings()
        ))
                .isInstanceOf(OcrProviderException.class)
                .hasMessageNotContaining("sk-secret")
                .hasMessageContaining("视觉识别模型调用失败");
    }

    @Test
    void testReturnsFailureWhenVisionModelProducesNoText() {
        FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory();
        runtimeFactory.runtime.nextImageResponse = " ";
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), runtimeFactory);

        var response = engine.test(enabledSettings());

        assertThat(response.success()).isFalse();
        assertThat(response.engine()).isEqualTo(OcrProvider.MODEL_VISION);
        assertThat(response.modelName()).isEqualTo(ModelConfigDefaults.VISION_MODEL);
    }

    @Test
    void testReturnsFailureWhenVisionModelDoesNotReadExpectedText() {
        FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory();
        runtimeFactory.runtime.nextImageResponse = "I cannot inspect images.";
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), runtimeFactory);

        var response = engine.test(enabledSettings());

        assertThat(response.success()).isFalse();
        assertThat(response.message()).contains("未正确识别测试图片");
    }

    @Test
    void recognizeFailsWhenVisionModelExceedsTimeout() {
        FakeRuntimeFactory runtimeFactory = new FakeRuntimeFactory();
        runtimeFactory.runtime.delayMillis = 1_500;
        ModelVisionOcrEngine engine = new ModelVisionOcrEngine(new FakeModelConfigService(), runtimeFactory);

        assertThatThrownBy(() -> engine.recognize(
                new OcrPageImage(Path.of("page.pdf"), 1, new byte[] {1}),
                settingsWithTimeout(1)
        ))
                .isInstanceOf(OcrProviderException.class)
                .hasMessageContaining("超时");
    }

    private static OcrSettingsSnapshot enabledSettings() {
        return new OcrSettingsSnapshot(true, OcrProvider.MODEL_VISION, true, 200, 20, 1000);
    }

    private static OcrSettingsSnapshot settingsWithTimeout(int timeoutSeconds) {
        return new OcrSettingsSnapshot(true, OcrProvider.MODEL_VISION, true, 200, timeoutSeconds, 1000);
    }

    private static final class FakeModelConfigService extends ModelConfigService {

        private FakeModelConfigService() {
            super(null);
        }

        @Override
        public ModelConfig requireActiveVisionConfigured() {
            return visionConfig();
        }

        private static ModelConfig visionConfig() {
            long now = System.currentTimeMillis();
            return new ModelConfig(
                    ModelConfigDefaults.ACTIVE_VISION_CONFIG_ID,
                    ModelConfigRole.VISION,
                    ModelProvider.DASHSCOPE,
                    ModelConfigDefaults.VISION_DISPLAY_NAME,
                    ModelConfigDefaults.BASE_URL,
                    "sk-vision",
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

    private static final class FakeRuntimeFactory implements AiRuntimeFactory {

        private final RecordingAiChatRuntime runtime = new RecordingAiChatRuntime();
        private ModelConfig lastConfig;

        @Override
        public AiChatRuntime chatRuntime(ModelConfig config) {
            lastConfig = config;
            return runtime;
        }

        @Override
        public AiEmbeddingRuntime embeddingRuntime(ModelConfig config) {
            throw new UnsupportedOperationException("Embedding runtime is not used by OCR tests");
        }
    }

    private static final class RecordingAiChatRuntime implements AiChatRuntime {

        private String nextImageResponse = "OCR TEST";
        private ModelConfigurationException failure;
        private long delayMillis;
        private String lastSystemPrompt;
        private String lastUserMessage;
        private byte[] lastImageBytes;
        private MimeType lastMimeType;

        @Override
        public Flux<String> stream(Prompt prompt) {
            throw new UnsupportedOperationException("Stream is not used by OCR tests");
        }

        @Override
        public Flux<String> stream(
                String systemPrompt,
                String userMessage,
                List<Advisor> advisors,
                Map<String, Object> advisorParams
        ) {
            throw new UnsupportedOperationException("Stream is not used by OCR tests");
        }

        @Override
        public String callText(String systemPrompt, String userMessage) {
            throw new UnsupportedOperationException("Text call is not used by OCR tests");
        }

        @Override
        public String callImage(String systemPrompt, String userMessage, byte[] imageBytes, MimeType mimeType) {
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            if (failure != null) {
                throw failure;
            }
            lastSystemPrompt = systemPrompt;
            lastUserMessage = userMessage;
            lastImageBytes = imageBytes;
            lastMimeType = mimeType;
            return nextImageResponse;
        }

        @Override
        public void testConnection(Prompt prompt) {
            throw new UnsupportedOperationException("Connection test is not used by OCR tests");
        }
    }
}
