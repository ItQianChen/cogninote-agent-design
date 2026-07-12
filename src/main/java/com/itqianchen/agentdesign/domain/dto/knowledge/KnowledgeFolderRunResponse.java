package com.itqianchen.agentdesign.domain.dto.knowledge;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;

/**
 * 知识库维护运行记录响应。
 */
public record KnowledgeFolderRunResponse(
        String id,
        KnowledgeFolderRunScopeType scopeType,
        String scopeId,
        KnowledgeFolderRunOperation operation,
        KnowledgeFolderRunStatus status,
        int scannedCount,
        int parsedCount,
        int skippedCount,
        int failedCount,
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        String phase,
        long progressCurrent,
        long progressTotal,
        String currentItem,
        Long queuedAt,
        Long startedAt,
        Long completedAt,
        Long durationMs,
        String errorMessage,
        String errorStage,
        String errorCode,
        String errorDetail,
        long createdAt,
        long updatedAt,
        Integer queuePosition
) {
    /**
     * 从领域运行记录构造接口响应。
     *
     * @param run 运行记录
     * @return 响应对象
     */
    public static KnowledgeFolderRunResponse from(KnowledgeFolderRun run) {
        return new KnowledgeFolderRunResponse(
                run.id(),
                run.scopeType(),
                run.scopeId(),
                run.operation(),
                run.status(),
                run.scannedCount(),
                run.parsedCount(),
                run.skippedCount(),
                run.failedCount(),
                run.indexedDocumentCount(),
                run.indexedChunkCount(),
                run.failedDocumentCount(),
                run.phase(),
                run.progressCurrent(),
                run.progressTotal(),
                run.currentItem(),
                run.queuedAt(),
                run.startedAt(),
                run.completedAt(),
                run.durationMs(),
                run.errorMessage(),
                run.errorStage(),
                run.errorCode(),
                run.errorDetail(),
                run.createdAt(),
                run.updatedAt(),
                null
        );
    }

    public KnowledgeFolderRunResponse withQueuePosition(Integer queuePosition) {
        return new KnowledgeFolderRunResponse(
                id,
                scopeType,
                scopeId,
                operation,
                status,
                scannedCount,
                parsedCount,
                skippedCount,
                failedCount,
                indexedDocumentCount,
                indexedChunkCount,
                failedDocumentCount,
                phase,
                progressCurrent,
                progressTotal,
                currentItem,
                queuedAt,
                startedAt,
                completedAt,
                durationMs,
                errorMessage,
                errorStage,
                errorCode,
                errorDetail,
                createdAt,
                updatedAt,
                queuePosition
        );
    }
}
