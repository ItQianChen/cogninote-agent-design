package com.itqianchen.agentdesign.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.search")
public record SearchProperties(
        int topK,
        double bm25Weight,
        double vectorWeight
) {
    public int normalizedTopK(Integer requestedTopK) {
        int value = requestedTopK == null ? topK : requestedTopK;
        return Math.clamp(value, 1, 50);
    }
}
