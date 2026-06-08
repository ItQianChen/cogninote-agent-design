package com.itqianchen.agentdesign.dto.model;

import com.itqianchen.agentdesign.domain.model.ModelConfig;
import com.itqianchen.agentdesign.domain.model.ModelConfigRole;

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
        Integer contextWindowTokens,
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
                config.role() == ModelConfigRole.CHAT
                        ? config.resolvedContextWindowTokens()
                        : null,
                config.active(),
                config.createdAt(),
                config.updatedAt()
        );
    }
}


