package com.itqianchen.agentdesign.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelConfigurationException;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-model-config",
        "app.storage.database-path=target/test-cogninote-model-config/cogninote.db",
        "server.address=127.0.0.1"
})
class ModelConfigServiceTests {

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
                null
        );
    }
}
