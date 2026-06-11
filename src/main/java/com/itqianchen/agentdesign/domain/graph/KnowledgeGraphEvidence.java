package com.itqianchen.agentdesign.domain.graph;

/**
 * 节点或边的证据回链。
 * <p>证据必须至少能回到 chunk_id；文件名、heading 等展示字段通过查询时 join 获取。</p>
 */
public record KnowledgeGraphEvidence(
        String id,
        String runId,
        String nodeId,
        String edgeId,
        String documentId,
        String chunkId,
        String quote,
        double confidence,
        long createdAt
) {
}
