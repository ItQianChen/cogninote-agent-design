package com.itqianchen.agentdesign.domain.graph;

/**
 * 单 chunk 的模型抽取缓存。
 * <p>该层与 scope 无关，保证目录图谱和全库图谱能复用同一份昂贵模型结果。</p>
 */
public record KnowledgeGraphChunkExtraction(
        String chunkId,
        String documentId,
        String contentHash,
        String promptVersion,
        String modelConfigId,
        KnowledgeGraphExtractionStatus status,
        String extractionJson,
        String errorMessage,
        Long extractedAt
) {
}
