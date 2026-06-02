package com.itqianchen.agentdesign.search;

public record RebuildIndexResponse(
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        long durationMs
) {
}
