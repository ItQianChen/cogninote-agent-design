package com.itqianchen.agentdesign.service.graph;

/**
 * 图谱 run 的内存进度。
 * <p>SSE 使用该对象实时推送，SQLite 只按节流频率保存快照。</p>
 */
public record KnowledgeGraphRunProgress(
        String runId,
        String status,
        String phase,
        int totalChunkCount,
        int processedChunkCount,
        int skippedChunkCount,
        int failedChunkCount
) {
}
