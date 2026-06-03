package com.itqianchen.agentdesign.dto.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;

public record ModelConfigResponse(
        String id,
        String role,
        String provider,
        String displayName,
        String baseUrl,
        boolean apiKeyConfigured,
        String apiKey,
        String modelName,
        Integer embeddingDimensions,
        Double temperature,
        Integer defaultTopK,
        boolean active,
        Long createdAt,
        Long updatedAt
) {
    public static ModelConfigResponse from(ModelConfig config) {
        return new ModelConfigResponse(
                config.id(),
                config.role().name(),
                config.provider().name(),
                config.displayName(),
                config.baseUrl(),
                config.hasApiKey(),
                config.apiKey(),
                config.modelName(),
                config.embeddingDimensions(),
                config.temperature(),
                config.defaultTopK(),
                config.active(),
                config.createdAt(),
                config.updatedAt()
        );
    }
}


