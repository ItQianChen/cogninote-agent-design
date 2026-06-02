package com.itqianchen.agentdesign.search;

public record IndexedChunk(
        String id,
        String documentId,
        int chunkIndex,
        String content,
        String contentHash,
        Integer pageNumber,
        String heading
) {
}
