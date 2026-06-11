package com.itqianchen.agentdesign.service.graph;

/**
 * 抽取阶段结果计数。
 */
public record GraphExtractionResult(
        int processedChunkCount,
        int skippedChunkCount,
        int failedChunkCount,
        boolean cancelled
) {
}
