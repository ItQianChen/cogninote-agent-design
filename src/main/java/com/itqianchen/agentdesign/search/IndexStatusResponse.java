package com.itqianchen.agentdesign.search;

public record IndexStatusResponse(
        String indexPath,
        long indexedDocumentCount,
        long indexedChunkCount,
        long parsedDocumentCount,
        long unindexedDocumentCount,
        Long lastIndexedAt,
        boolean embeddingConfigured
) {
}
