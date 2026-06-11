package com.itqianchen.agentdesign.domain.graph;

/**
 * scope 内合并后的图谱节点。
 */
public record KnowledgeGraphNode(
        String id,
        KnowledgeGraphScopeType scopeType,
        String scopeId,
        String canonicalName,
        String displayName,
        String nodeType,
        String description,
        double confidence,
        int mentionCount,
        long createdAt,
        long updatedAt
) {
}
