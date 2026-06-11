package com.itqianchen.agentdesign.dto.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphView;

/**
 * 图谱派生视图响应。
 */
public record KnowledgeGraphViewResponse(
        String viewType,
        JsonNode payload,
        String generatedFromRunId,
        long createdAt,
        long updatedAt
) {
    public static KnowledgeGraphViewResponse from(KnowledgeGraphView view, JsonNode payload) {
        return new KnowledgeGraphViewResponse(
                view.viewType().name(),
                payload,
                view.generatedFromRunId(),
                view.createdAt(),
                view.updatedAt()
        );
    }
}
