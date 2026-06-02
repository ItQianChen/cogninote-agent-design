package com.itqianchen.agentdesign.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.embedding")
public record EmbeddingProperties(
        int dimensions,
        int batchSize
) {
    public int normalizedBatchSize() {
        return Math.clamp(batchSize, 1, 128);
    }
}
