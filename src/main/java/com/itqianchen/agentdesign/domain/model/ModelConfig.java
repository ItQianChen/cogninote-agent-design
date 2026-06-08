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
        Integer contextWindowTokens,
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

    public int resolvedContextWindowTokens() {
        if (role != ModelConfigRole.CHAT) {
            return 0;
        }
        return contextWindowTokens == null
                ? ModelConfigDefaults.CONTEXT_WINDOW_TOKENS
                : Math.clamp(
                        contextWindowTokens,
                        ModelConfigDefaults.MIN_CONTEXT_WINDOW_TOKENS,
                        ModelConfigDefaults.MAX_CONTEXT_WINDOW_TOKENS
                );
    }
}


