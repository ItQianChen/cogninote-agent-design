package com.itqianchen.agentdesign.dto.graph;

import jakarta.validation.constraints.NotBlank;

/**
 * 知识图谱重建请求。
 */
public record KnowledgeGraphRebuildRequest(
        @NotBlank String scopeType,
        String scopeId
) {
}
