package com.itqianchen.agentdesign.domain.graph;

/**
 * 知识图谱重建 run 的持久化快照。
 * <p>run 同时承担防重入、进度恢复和历史记录职责。</p>
 */
public record KnowledgeGraphRun(
        String id,
        KnowledgeGraphScopeType scopeType,
        String scopeId,
        KnowledgeGraphRunStatus status,
        String modelConfigId,
        String promptVersion,
        int totalChunkCount,
        int processedChunkCount,
        int skippedChunkCount,
        int extractedNodeCount,
        int extractedEdgeCount,
        int failedChunkCount,
        String errorMessage,
        Long startedAt,
        Long completedAt,
        long createdAt,
        long updatedAt
) {
}
