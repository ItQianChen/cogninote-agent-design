package com.itqianchen.agentdesign.dto.graph;

/**
 * scope 级图谱状态快照。
 */
public record KnowledgeGraphStatusResponse(
        String scopeType,
        String scopeId,
        String scopeName,
        KnowledgeGraphRunResponse latestRun,
        int nodeCount,
        int edgeCount,
        boolean mindmapReady,
        boolean graphReady,
        Long generatedAt
) {
}
