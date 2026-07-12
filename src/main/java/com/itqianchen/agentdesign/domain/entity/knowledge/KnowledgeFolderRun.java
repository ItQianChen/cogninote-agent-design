package com.itqianchen.agentdesign.domain.entity.knowledge;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;

/**
 * 知识库维护任务记录。
 *
 * <p>该记录既是本地 FIFO 队列的恢复事实源，也是完成后的维护历史。未结束任务的
 * startedAt、completedAt 和 durationMs 可以为空；调用方必须以 status 判断任务生命周期。</p>
 */
public record KnowledgeFolderRun(
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
        String failuresJson,
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
        long updatedAt
) {
    /** 兼容不携带结构化失败诊断的既有构造调用。 */
    public KnowledgeFolderRun(
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
            String failuresJson,
            String phase,
            long progressCurrent,
            long progressTotal,
            String currentItem,
            Long queuedAt,
            Long startedAt,
            Long completedAt,
            Long durationMs,
            String errorMessage,
            long createdAt,
            long updatedAt
    ) {
        this(
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
                failuresJson,
                phase,
                progressCurrent,
                progressTotal,
                currentItem,
                queuedAt,
                startedAt,
                completedAt,
                durationMs,
                errorMessage,
                null,
                null,
                null,
                createdAt,
                updatedAt
        );
    }
}
