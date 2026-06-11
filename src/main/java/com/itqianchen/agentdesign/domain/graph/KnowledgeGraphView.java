package com.itqianchen.agentdesign.domain.graph;

/**
 * 图谱派生视图缓存。
 * <p>payloadJson 是前端契约，事实数据仍以 nodes/edges/evidence 表为准。</p>
 */
public record KnowledgeGraphView(
        String id,
        KnowledgeGraphScopeType scopeType,
        String scopeId,
        KnowledgeGraphViewType viewType,
        String payloadJson,
        String generatedFromRunId,
        long createdAt,
        long updatedAt
) {
}
