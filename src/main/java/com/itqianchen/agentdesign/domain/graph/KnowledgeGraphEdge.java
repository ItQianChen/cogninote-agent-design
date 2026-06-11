package com.itqianchen.agentdesign.domain.graph;

/**
 * scope 内合并后的图谱关系。
 */
public record KnowledgeGraphEdge(
        String id,
        KnowledgeGraphScopeType scopeType,
        String scopeId,
        String sourceNodeId,
        String targetNodeId,
        String relationType,
        String description,
        double confidence,
        int mentionCount,
        long createdAt,
        long updatedAt
) {
}
