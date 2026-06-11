package com.itqianchen.agentdesign.mapper.graph;

/**
 * 证据列表查询结果。
 * <p>展示层需要 chunk 与文档元信息，因此这里用专门 row 承接 join 结果。</p>
 */
public record KnowledgeGraphEvidenceDetailRow(
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
}
