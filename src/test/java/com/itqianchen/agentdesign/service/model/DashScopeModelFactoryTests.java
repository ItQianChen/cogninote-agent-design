package com.itqianchen.agentdesign.service.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;

class DashScopeModelFactoryTests {

    @Test
    void chatEndpointRoutesTraditionalQwenModelsToTextGeneration() {
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen-plus").multiModel())
                .isFalse();
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen-max").multiModel())
                .isFalse();
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen3-max").multiModel())
                .isFalse();
    }

    @Test
    void chatEndpointRoutesNewDashScopeMultimodalModelsToMultimodalGeneration() {
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen3.6-plus").multiModel())
                .isTrue();
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen3.7-plus").multiModel())
                .isTrue();
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen3.5-omni-plus").multiModel())
                .isTrue();
        assertThat(DashScopeModelFactory.DashScopeChatEndpoint.fromModel("qwen3-vl-plus").multiModel())
                .isTrue();
    }

    @Test
    void embeddingModelCacheSeparatesDocumentAndQueryTextType() {
        DashScopeModelFactory factory = new DashScopeModelFactory(observationRegistryProvider());
        ModelConfig config = embeddingConfig();

        EmbeddingModel documentModel = factory.embeddingModel(config, "document");
        EmbeddingModel sameDocumentModel = factory.embeddingModel(config, "document");
        EmbeddingModel queryModel = factory.embeddingModel(config, "query");

        assertThat(sameDocumentModel).isSameAs(documentModel);
        assertThat(queryModel).isNotSameAs(documentModel);
    }

    private ObjectProvider<ObservationRegistry> observationRegistryProvider() {
        @SuppressWarnings("unchecked")
        ObjectProvider<ObservationRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(ObservationRegistry.NOOP);
        return provider;
    }

    private ModelConfig embeddingConfig() {
        return new ModelConfig(
                "embedding",
                ModelConfigRole.EMBEDDING,
                ModelProvider.DASHSCOPE,
                "DashScope Embedding",
                ModelConfigDefaults.BASE_URL,
                "test-api-key",
                ModelConfigDefaults.EMBEDDING_MODEL,
                ModelConfigDefaults.EMBEDDING_DIMENSIONS,
                null,
                null,
                null,
                true,
                1L,
                1L
        );
    }
}
