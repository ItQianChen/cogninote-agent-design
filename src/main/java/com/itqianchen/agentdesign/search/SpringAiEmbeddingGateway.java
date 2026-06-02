package com.itqianchen.agentdesign.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SpringAiEmbeddingGateway implements EmbeddingGateway {

    private final Optional<EmbeddingModel> embeddingModel;
    private final EmbeddingProperties embeddingProperties;
    private final String embeddingProvider;
    private final String dashscopeApiKey;

    public SpringAiEmbeddingGateway(
            Optional<EmbeddingModel> embeddingModel,
            EmbeddingProperties embeddingProperties,
            @Value("${spring.ai.model.embedding:none}") String embeddingProvider,
            @Value("${spring.ai.dashscope.api-key:}") String dashscopeApiKey
    ) {
        this.embeddingModel = embeddingModel;
        this.embeddingProperties = embeddingProperties;
        this.embeddingProvider = embeddingProvider;
        this.dashscopeApiKey = dashscopeApiKey;
    }

    @Override
    public boolean isAvailable() {
        if (embeddingModel.isEmpty() || "none".equalsIgnoreCase(embeddingProvider)) {
            return false;
        }

        // Phase 3 defaults to DashScope. Keep the app bootable when the starter is present
        // but the local API key is intentionally absent.
        return !"dashscope".equalsIgnoreCase(embeddingProvider) || StringUtils.hasText(dashscopeApiKey);
    }

    @Override
    public int dimensions() {
        return embeddingProperties.dimensions();
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) {
        if (!isAvailable()) {
            throw new EmbeddingUnavailableException("Embedding model is not configured");
        }

        EmbeddingModel model = embeddingModel.orElseThrow(() ->
                new EmbeddingUnavailableException("Embedding model is not configured"));

        List<float[]> vectors = new ArrayList<>();
        int batchSize = embeddingProperties.normalizedBatchSize();
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(start + batchSize, texts.size());
            vectors.addAll(model.embed(texts.subList(start, end)));
        }

        for (float[] vector : vectors) {
            if (vector.length != embeddingProperties.dimensions()) {
                throw new EmbeddingUnavailableException(
                        "Embedding dimensions mismatch: expected "
                                + embeddingProperties.dimensions()
                                + " but got "
                                + vector.length
                );
            }
        }

        return vectors;
    }
}
