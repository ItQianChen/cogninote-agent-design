package com.itqianchen.agentdesign.dto.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;

public record LegacyModelConfigResponse(
        String provider,
        String displayName,
        String baseUrl,
        boolean apiKeyConfigured,
        String apiKey,
        String chatModel,
        String embeddingModel,
        int embeddingDimensions,
        double temperature,
        int topK,
        Long updatedAt,
        ModelConfigResponse chat,
        ModelConfigResponse embedding
) {
    public static LegacyModelConfigResponse from(ModelConfig chat, ModelConfig embedding) {
        return new LegacyModelConfigResponse(
                chat.provider().name(),
                chat.displayName(),
                chat.baseUrl(),
                chat.hasApiKey(),
                chat.apiKey(),
                chat.modelName(),
                embedding.modelName(),
                embedding.resolvedEmbeddingDimensions(),
                chat.resolvedTemperature(),
                chat.resolvedDefaultTopK(),
                Math.max(chat.updatedAt(), embedding.updatedAt()),
                ModelConfigResponse.from(chat),
                ModelConfigResponse.from(embedding)
        );
    }
}
