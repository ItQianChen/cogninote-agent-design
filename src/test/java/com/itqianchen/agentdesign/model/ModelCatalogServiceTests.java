package com.itqianchen.agentdesign.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.itqianchen.agentdesign.domain.model.ModelCapability;
import com.itqianchen.agentdesign.domain.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.dto.model.ModelConfigRequest;
import com.itqianchen.agentdesign.dto.model.ModelOptionsResponse;
import com.itqianchen.agentdesign.repository.model.ModelConfigRepository;
import com.itqianchen.agentdesign.service.model.ModelCatalogService;
import com.itqianchen.agentdesign.service.model.ModelConfigService;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ModelCatalogServiceTests {

    @Test
    void fetchModelsUsesDashScopeDefaultModelsEndpointAndClassifiesResults() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelCatalogService service = new ModelCatalogService(
                new ModelConfigService(emptyRepository()),
                builder
        );

        server.expect(requestTo("https://dashscope.aliyuncs.com/compatible-mode/v1/models"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andRespond(withSuccess("""
                        {
                          "object": "list",
                          "data": [
                            { "id": "qwen-plus" },
                            { "id": "text-embedding-v4" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ModelOptionsResponse response = service.fetchModels(new ModelConfigRequest(
                ModelConfigRole.CHAT.name(),
                "DASHSCOPE",
                "DashScope",
                // DashScope provider 固定使用百炼默认地址，这里传入自定义 host 是为了防止旧逻辑回退。
                "https://example.test/compatible-mode/v1",
                "sk-test",
                "qwen-plus",
                "qwen-plus",
                "text-embedding-v4",
                1024,
                0.7,
                8,
                8,
                ModelConfigDefaults.CONTEXT_WINDOW_TOKENS
        ));

        assertThat(response.models())
                .extracting("id", "capability")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("qwen-plus", ModelCapability.CHAT),
                        org.assertj.core.groups.Tuple.tuple("text-embedding-v4", ModelCapability.EMBEDDING)
                );
        server.verify();
    }

    @Test
    void fetchModelsUsesCustomOpenAiCompatibleModelsEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ModelCatalogService service = new ModelCatalogService(
                new ModelConfigService(emptyRepository()),
                builder
        );

        server.expect(requestTo("https://api.example.test/v1/models"))
                .andExpect(header("Authorization", "Bearer sk-test"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            { "id": "gpt-4.1-mini" },
                            { "id": "text-embedding-3-small" }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        ModelOptionsResponse response = service.fetchModels(new ModelConfigRequest(
                ModelConfigRole.CHAT.name(),
                "OPENAI_COMPATIBLE",
                "OpenAI-compatible",
                "https://api.example.test/v1/chat/completions",
                "sk-test",
                "gpt-4.1-mini",
                "gpt-4.1-mini",
                "text-embedding-3-small",
                1536,
                0.7,
                8,
                8,
                ModelConfigDefaults.CONTEXT_WINDOW_TOKENS
        ));

        assertThat(response.models())
                .extracting("id", "capability")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("gpt-4.1-mini", ModelCapability.CHAT),
                        org.assertj.core.groups.Tuple.tuple("text-embedding-3-small", ModelCapability.EMBEDDING)
                );
        server.verify();
    }

    private static ModelConfigRepository emptyRepository() {
        return new ModelConfigRepository(null) {
            @Override
            public java.util.Optional<com.itqianchen.agentdesign.domain.model.ModelConfig> findActive(ModelConfigRole role) {
                return Optional.empty();
            }
        };
    }
}
