package com.itqianchen.agentdesign.service.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.model.ModelProvider;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleEmbeddingClientTests {

    @Test
    void embedBatchPostsToCustomEmbeddingsEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleEmbeddingClient client = new OpenAiCompatibleEmbeddingClient(builder);

        server.expect(once(), requestTo("https://api.example.test/v1/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer sk-test"))
                .andRespond(withSuccess("""
                        {
                          "data": [
                            { "embedding": [0.1, 0.2, 0.3] },
                            { "embedding": [0.4, 0.5, 0.6] }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedBatch(config(), List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(vectors.get(1)).containsExactly(0.4f, 0.5f, 0.6f);
        server.verify();
    }

    private static ModelConfig config() {
        return new ModelConfig(
                "active",
                ModelConfigRole.EMBEDDING,
                ModelProvider.OPENAI_COMPATIBLE,
                "OpenAI-compatible",
                "https://api.example.test/v1/models",
                "sk-test",
                "text-embedding-3-small",
                1536,
                null,
                null,
                true,
                1L,
                2L
        );
    }
}
