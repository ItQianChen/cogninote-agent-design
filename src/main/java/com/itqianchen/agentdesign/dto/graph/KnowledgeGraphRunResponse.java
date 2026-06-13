package com.itqianchen.agentdesign.dto.graph;

import com.itqianchen.agentdesign.domain.graph.KnowledgeGraphRun;

/**
 * 知识图谱 run 状态响应。
 */
public record KnowledgeGraphRunResponse(
        String runId,
        String scopeType,
        String scopeId,
        String status,
        String modelConfigId,
        String promptVersion,
        int totalChunkCount,
        int processedChunkCount,
        int skippedChunkCount,
        int extractedNodeCount,
        int extractedEdgeCount,
        int failedChunkCount,
        String errorMessage,
        Long startedAt,
        Long completedAt,
        long createdAt,
        long updatedAt
) {
    private static final String LEGACY_ORPHAN_RUN_MESSAGE = "Application restarted before graph run completed";
    private static final String ORPHAN_RUN_MESSAGE = "上次知识图谱生成因应用重启被中断，请重新生成。";

    /**
     * 将图谱运行记录转换为接口响应。
     *
     * <p>允许传入 null，便于控制器在没有最近一次运行时返回空状态而不是抛出 500。</p>
     *
     * @param run 图谱运行记录
     * @return 运行状态响应；没有运行记录时返回 null
     */
    public static KnowledgeGraphRunResponse from(KnowledgeGraphRun run) {
        if (run == null) {
            return null;
        }
        return new KnowledgeGraphRunResponse(
                run.id(),
                run.scopeType().name(),
                run.scopeId(),
                run.status().name(),
                run.modelConfigId(),
                run.promptVersion(),
                run.totalChunkCount(),
                run.processedChunkCount(),
                run.skippedChunkCount(),
                run.extractedNodeCount(),
                run.extractedEdgeCount(),
                run.failedChunkCount(),
                normalizeErrorMessage(run.errorMessage()),
                run.startedAt(),
                run.completedAt(),
                run.createdAt(),
                run.updatedAt()
        );
    }

    private static String normalizeErrorMessage(String errorMessage) {
        if (errorMessage == null || errorMessage.isBlank()) {
            return errorMessage;
        }
        if (LEGACY_ORPHAN_RUN_MESSAGE.equals(errorMessage.strip())) {
            return ORPHAN_RUN_MESSAGE;
        }
        return errorMessage;
    }
}
