package com.itqianchen.agentdesign.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.entity.model.ModelConfig;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.exception.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.enums.model.ModelProvider;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.domain.dto.model.ModelConfigUpsertRequest;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "server.address=127.0.0.1"
})
class ModelConfigServiceTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private ModelConfigService modelConfigService;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearModelConfigs();
    }

    @Test
    void activeDefaultsAreSplitByRole() {
        ModelConfig chat = modelConfigService.activeChatOrDefault();
        ModelConfig embedding = modelConfigService.activeEmbeddingOrDefault();
        ModelConfig vision = modelConfigService.activeVisionOrDefault();

        assertThat(chat.role()).isEqualTo(ModelConfigRole.CHAT);
        assertThat(chat.provider()).isEqualTo(ModelProvider.DASHSCOPE);
        assertThat(chat.baseUrl()).isEqualTo(ModelConfigDefaults.BASE_URL);
        assertThat(chat.modelName()).isEqualTo("qwen-plus");
        assertThat(chat.resolvedTemperature()).isEqualTo(0.7);
        assertThat(chat.resolvedDefaultTopK()).isEqualTo(8);
        assertThat(chat.resolvedContextWindowTokens()).isEqualTo(ModelConfigDefaults.CONTEXT_WINDOW_TOKENS);

        assertThat(embedding.role()).isEqualTo(ModelConfigRole.EMBEDDING);
        assertThat(embedding.modelName()).isEqualTo("text-embedding-v4");
        assertThat(embedding.resolvedEmbeddingDimensions()).isEqualTo(1024);
        assertThat(embedding.contextWindowTokens()).isNull();
        assertThat(embedding.resolvedContextWindowTokens()).isZero();

        assertThat(vision.role()).isEqualTo(ModelConfigRole.VISION);
        assertThat(vision.modelName()).isEqualTo(ModelConfigDefaults.VISION_MODEL);
        assertThat(vision.temperature()).isEqualTo(ModelConfigDefaults.VISION_TEMPERATURE);
        assertThat(vision.contextWindowTokens()).isNull();
        assertThat(vision.hasApiKey()).isFalse();
    }

    @Test
    void createAndActivateChatDoesNotOverwriteEmbedding() {
        ModelConfig embedding = modelConfigService.create(embeddingRequest(
                "DASHSCOPE",
                "Embedding A",
                "sk-embedding",
                ModelConfigDefaults.BASE_URL,
                "text-embedding-v4",
                1024
        ));

        ModelConfig chat = modelConfigService.create(chatRequest(
                "OPENAI_COMPATIBLE",
                "Chat A",
                "sk-chat",
                "https://api.example.test/v1/chat/completions",
                "gpt-4.1-mini",
                0.3,
                12
        ));
        modelConfigService.activate(chat.id());

        assertThat(modelConfigService.requireActiveChatConfigured().modelName()).isEqualTo("gpt-4.1-mini");
        assertThat(modelConfigService.requireActiveEmbeddingConfigured().modelName()).isEqualTo("text-embedding-v4");
        assertThat(modelConfigService.requireActiveEmbeddingConfigured().apiKey()).isEqualTo(embedding.apiKey());
    }

    @Test
    void openAiCompatibleProviderPersistsCustomBaseUrlPerRole() {
        ModelConfig chat = modelConfigService.create(chatRequest(
                "OPENAI_COMPATIBLE",
                "Chat A",
                "sk-chat",
                "https://api.example.test/v1/chat/completions",
                "gpt-4.1-mini",
                0.4,
                10
        ));

        assertThat(chat.provider()).isEqualTo(ModelProvider.OPENAI_COMPATIBLE);
        assertThat(chat.baseUrl()).isEqualTo("https://api.example.test/v1");
        assertThat(chat.modelName()).isEqualTo("gpt-4.1-mini");
    }

    @Test
    void chatContextWindowCanBeCustomizedAndReturnedInSettings() {
        ModelConfig chat = modelConfigService.create(chatRequest(
                "DASHSCOPE",
                "Chat 64K",
                "sk-test",
                ModelConfigDefaults.BASE_URL,
                "qwen-plus",
                0.7,
                8,
                64_000
        ));

        assertThat(chat.contextWindowTokens()).isEqualTo(64_000);
        assertThat(modelConfigService.activeChatOrDefault().resolvedContextWindowTokens()).isEqualTo(64_000);
        assertThat(modelConfigService.settingsSnapshot(ModelConfigRole.CHAT).selectedConfig().contextWindowTokens())
                .isEqualTo(64_000);
    }

    @Test
    void embeddingConfigKeepsContextWindowEmpty() {
        ModelConfig embedding = modelConfigService.create(embeddingRequest(
                "DASHSCOPE",
                "Embedding A",
                "sk-embedding",
                ModelConfigDefaults.BASE_URL,
                "text-embedding-v4",
                1024
        ));

        assertThat(embedding.contextWindowTokens()).isNull();
        assertThat(modelConfigService.settingsSnapshot(ModelConfigRole.EMBEDDING)
                .selectedConfig()
                .contextWindowTokens()).isNull();
    }

    @Test
    void updateWithBlankApiKeyKeepsExistingSecret() {
        ModelConfig saved = modelConfigService.create(chatRequest(
                "DASHSCOPE",
                "Chat A",
                "sk-test",
                ModelConfigDefaults.BASE_URL,
                "qwen-plus",
                0.7,
                8
        ));

        modelConfigService.update(saved.id(), chatRequest(
                "DASHSCOPE",
                "Chat A",
                "",
                ModelConfigDefaults.BASE_URL,
                "qwen-max",
                0.2,
                6
        ));

        ModelConfig config = modelConfigService.requireActiveChatConfigured();

        assertThat(config.apiKey()).isEqualTo("sk-test");
        assertThat(config.modelName()).isEqualTo("qwen-max");
        assertThat(config.resolvedTemperature()).isEqualTo(0.2);
        assertThat(config.resolvedDefaultTopK()).isEqualTo(6);
    }

    @Test
    void updateSettingsCanExplicitlyClearApiKey() {
        ModelConfig saved = modelConfigService.create(visionRequest(
                "DASHSCOPE",
                "Vision A",
                "sk-vision",
                ModelConfigDefaults.BASE_URL,
                "qwen3-vl-plus",
                0.0
        ));

        modelConfigService.updateSettings(saved.id(), new ModelConfigUpsertRequest(
                ModelConfigRole.VISION.name(),
                "DASHSCOPE",
                "Vision A",
                ModelConfigDefaults.BASE_URL,
                "",
                "qwen3-vl-plus",
                0.0,
                null,
                null,
                null,
                null,
                null,
                null,
                true
        ));

        ModelConfig config = modelConfigService.activeVisionOrDefault();

        assertThat(config.hasApiKey()).isFalse();
        assertThat(config.apiKey()).isEmpty();
        assertThat(modelConfigService.settingsSnapshot(ModelConfigRole.VISION)
                .selectedConfig()
                .apiKeyConfigured()).isFalse();
    }

    @Test
    void deleteOnlyActiveConfigCreatesDefaultFallback() {
        ModelConfig chat = modelConfigService.create(chatRequest(
                "DASHSCOPE",
                "Chat A",
                "sk-test",
                ModelConfigDefaults.BASE_URL,
                "qwen-plus",
                0.7,
                8
        ));

        modelConfigService.delete(chat.id());

        ModelConfig fallback = modelConfigService.activeChatOrDefault();
        assertThat(fallback.active()).isTrue();
        assertThat(fallback.role()).isEqualTo(ModelConfigRole.CHAT);
        assertThat(fallback.modelName()).isEqualTo(ModelConfigDefaults.CHAT_MODEL);
        assertThat(fallback.hasApiKey()).isFalse();
    }

    @Test
    void deleteOnlyActiveVisionConfigCreatesDefaultFallback() {
        ModelConfig vision = modelConfigService.create(visionRequest(
                "DASHSCOPE",
                "Vision A",
                "sk-vision",
                ModelConfigDefaults.BASE_URL,
                "qwen3-vl-plus",
                0.0
        ));

        modelConfigService.delete(vision.id());

        ModelConfig fallback = modelConfigService.activeVisionOrDefault();
        assertThat(fallback.active()).isTrue();
        assertThat(fallback.role()).isEqualTo(ModelConfigRole.VISION);
        assertThat(fallback.modelName()).isEqualTo(ModelConfigDefaults.VISION_MODEL);
        assertThat(fallback.temperature()).isEqualTo(ModelConfigDefaults.VISION_TEMPERATURE);
        assertThat(fallback.hasApiKey()).isFalse();
    }

    @Test
    void deleteActiveConfigPromotesRemainingConfig() {
        ModelConfig active = modelConfigService.create(chatRequest(
                "DASHSCOPE",
                "Chat A",
                "sk-active",
                ModelConfigDefaults.BASE_URL,
                "qwen-plus",
                0.7,
                8
        ));
        ModelConfig standby = modelConfigService.create(chatRequest(
                "DASHSCOPE",
                "Chat B",
                "sk-standby",
                ModelConfigDefaults.BASE_URL,
                "qwen-max",
                0.4,
                6
        ));

        modelConfigService.delete(active.id());

        ModelConfig promoted = modelConfigService.requireActiveChatConfigured();
        assertThat(promoted.id()).isEqualTo(standby.id());
        assertThat(promoted.active()).isTrue();
        assertThat(promoted.modelName()).isEqualTo("qwen-max");
    }

    @Test
    void saveLegacyRequestSplitsChatAndEmbedding() {
        modelConfigService.save(new ModelConfigRequest(
                null,
                "DASHSCOPE",
                "DashScope",
                ModelConfigDefaults.BASE_URL,
                "sk-test",
                null,
                "qwen-max",
                "text-embedding-v4",
                1024,
                null,
                null,
                null,
                0.3,
                12,
                null,
                ModelConfigDefaults.CONTEXT_WINDOW_TOKENS
        ));

        assertThat(modelConfigService.requireActiveChatConfigured().modelName()).isEqualTo("qwen-max");
        assertThat(modelConfigService.requireActiveEmbeddingConfigured().modelName()).isEqualTo("text-embedding-v4");
    }

    @Test
    void saveRejectsInvalidBaseUrl() {
        assertThatThrownBy(() -> modelConfigService.create(chatRequest("OPENAI_COMPATIBLE", "Chat A", "sk-test",
                "ftp://example.com", "qwen-plus", 0.7, 8)))
                .isInstanceOf(ModelConfigurationException.class)
                .hasMessageContaining("Base URL");
    }

    @Test
    void requireConfiguredFailsWithoutApiKey() {
        assertThatThrownBy(() -> modelConfigService.requireActiveChatConfigured())
                .isInstanceOf(ModelConfigurationException.class)
                .hasMessageContaining("API Key");
    }

    @Test
    void requireActiveVisionConfiguredFailsWithoutApiKey() {
        assertThatThrownBy(() -> modelConfigService.requireActiveVisionConfigured())
                .isInstanceOf(ModelConfigurationException.class)
                .hasMessageContaining("Vision API Key");
    }

    private static ModelConfigRequest chatRequest(
            String provider,
            String displayName,
            String apiKey,
            String baseUrl,
            String modelName,
            double temperature,
            int topK
    ) {
        return chatRequest(provider, displayName, apiKey, baseUrl, modelName, temperature, topK,
                ModelConfigDefaults.CONTEXT_WINDOW_TOKENS);
    }

    private static ModelConfigRequest chatRequest(
            String provider,
            String displayName,
            String apiKey,
            String baseUrl,
            String modelName,
            double temperature,
            int topK,
            int contextWindowTokens
    ) {
        return new ModelConfigRequest(
                ModelConfigRole.CHAT.name(),
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                modelName,
                null,
                null,
                null,
                null,
                null,
                temperature,
                topK,
                topK,
                contextWindowTokens
        );
    }

    private static ModelConfigRequest embeddingRequest(
            String provider,
            String displayName,
            String apiKey,
            String baseUrl,
            String modelName,
            int dimensions
    ) {
        return new ModelConfigRequest(
                ModelConfigRole.EMBEDDING.name(),
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                null,
                modelName,
                dimensions,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static ModelConfigRequest visionRequest(
            String provider,
            String displayName,
            String apiKey,
            String baseUrl,
            String modelName,
            double temperature
    ) {
        return new ModelConfigRequest(
                ModelConfigRole.VISION.name(),
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                modelName,
                null,
                null,
                null,
                null,
                null,
                temperature,
                null,
                null,
                null
        );
    }
}
