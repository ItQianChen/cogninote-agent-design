package com.itqianchen.agentdesign.search;

public record SearchHitResponse(
        String chunkId,
        String documentId,
        String fileName,
        String sourcePath,
        String heading,
        Integer pageNumber,
        String preview,
        double score,
        Double keywordScore,
        Double vectorScore
) {
}
