package com.itqianchen.agentdesign.dto.graph;

import com.itqianchen.agentdesign.mapper.graph.KnowledgeGraphEvidenceDetailRow;

/**
 * 节点/边证据响应。
 */
public record KnowledgeGraphEvidenceResponse(
        String id,
        String runId,
        String nodeId,
        String edgeId,
        String documentId,
        String chunkId,
        String quote,
        double confidence,
        long createdAt,
        String fileName,
        String sourcePath,
        String heading,
        Integer pageNumber,
        int chunkIndex,
        String nodeDisplayName,
        String nodeType,
        String edgeRelationType,
        String edgeSourceName,
        String edgeTargetName
) {
    public static KnowledgeGraphEvidenceResponse from(KnowledgeGraphEvidenceDetailRow row) {
        return new KnowledgeGraphEvidenceResponse(
                row.id(),
                row.runId(),
                row.nodeId(),
                row.edgeId(),
                row.documentId(),
                row.chunkId(),
                row.quote(),
                row.confidence(),
                row.createdAt(),
                row.fileName(),
                row.sourcePath(),
                row.heading(),
                row.pageNumber(),
                row.chunkIndex(),
                row.nodeDisplayName(),
                row.nodeType(),
                row.edgeRelationType(),
                row.edgeSourceName(),
                row.edgeTargetName()
        );
    }
}
