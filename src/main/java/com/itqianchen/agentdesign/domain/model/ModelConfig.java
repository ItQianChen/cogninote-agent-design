package com.itqianchen.agentdesign.domain.model;

public record ModelConfig(
        String id,
        ModelConfigRole role,
        ModelProvider provider,
        String displayName,
        String baseUrl,
        String apiKey,
        String modelName,
        Integer embeddingDimensions,
        Double temperature,
        Integer defaultTopK,
        boolean active,
        long createdAt,
        long updatedAt
) {
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public int resolvedEmbeddingDimensions() {
        return embeddingDimensions == null
                ? ModelConfigDefaults.EMBEDDING_DIMENSIONS
                : embeddingDimensions;
    }

    public double resolvedTemperature() {
        return temperature == null ? ModelConfigDefaults.TEMPERATURE : temperature;
    }

    public int resolvedDefaultTopK() {
        return defaultTopK == null ? ModelConfigDefaults.TOP_K : defaultTopK;
    }
}


