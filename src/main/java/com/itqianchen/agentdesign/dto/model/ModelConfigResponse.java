package com.itqianchen.agentdesign.dto.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;

public record ModelConfigResponse(
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
        Long updatedAt
) {
    public static ModelConfigResponse from(ModelConfig config) {
        return new ModelConfigResponse(
                config.provider().name(),
                config.displayName(),
                config.baseUrl(),
                config.hasApiKey(),
                config.apiKey(),
                config.chatModel(),
                config.embeddingModel(),
                config.embeddingDimensions(),
                config.temperature(),
                config.topK(),
                config.updatedAt()
        );
    }
}


