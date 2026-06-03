package com.itqianchen.agentdesign.service.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.search.EmbeddingUnavailableException;
import com.itqianchen.agentdesign.service.model.OpenAiCompatibleUrls;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OpenAiCompatibleEmbeddingClient {

    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingClient(RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.build();
    }

    public List<float[]> embedBatch(ModelConfig config, List<String> texts) {
        try {
            JsonNode response = restClient.post()
                    .uri(OpenAiCompatibleUrls.embeddingsUri(config.baseUrl()))
                    .headers(headers -> headers.setBearerAuth(config.apiKey()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("model", config.modelName(), "input", texts))
                    .retrieve()
                    .body(JsonNode.class);
            return parseEmbeddings(response);
        } catch (RestClientException ex) {
            throw new EmbeddingUnavailableException("OpenAI-compatible embedding failed: " + ex.getMessage(), ex);
        }
    }

    private static List<float[]> parseEmbeddings(JsonNode response) {
        JsonNode data = response == null ? null : response.path("data");
        if (data == null || !data.isArray()) {
            throw new EmbeddingUnavailableException("OpenAI-compatible embedding response does not contain data[]");
        }

        List<float[]> vectors = new ArrayList<>();
        for (JsonNode item : data) {
            JsonNode embedding = item.path("embedding");
            if (!embedding.isArray()) {
                throw new EmbeddingUnavailableException("OpenAI-compatible embedding item does not contain embedding[]");
            }
            float[] vector = new float[embedding.size()];
            for (int index = 0; index < embedding.size(); index++) {
                vector[index] = (float) embedding.get(index).asDouble();
            }
            vectors.add(vector);
        }
        return vectors;
    }
}
