package com.itqianchen.agentdesign.domain.dto.knowledge;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import java.util.List;

/**
 * 知识库维护记录详情响应。
 *
 * <p>详情接口携带失败明细和目录展示信息；列表接口保持轻量，避免分页数据被 failures_json 撑大。</p>
 */
public record KnowledgeFolderRunDetailResponse(
        String id,
        KnowledgeFolderRunScopeType scopeType,
        String scopeId,
        String folderDisplayName,
        String folderPath,
        KnowledgeFolderRunOperation operation,
        KnowledgeFolderRunStatus status,
        int scannedCount,
        int parsedCount,
        int skippedCount,
        int failedCount,
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        List<DocumentFailureResponse> failures,
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
    public static KnowledgeFolderRunDetailResponse from(
            KnowledgeFolderRun run,
            KnowledgeFolder folder,
            List<DocumentFailureResponse> failures
    ) {
        return new KnowledgeFolderRunDetailResponse(
                run.id(),
                run.scopeType(),
                run.scopeId(),
                folder == null ? null : folder.displayName(),
                folder == null ? null : folder.folderPath(),
                run.operation(),
                run.status(),
                run.scannedCount(),
                run.parsedCount(),
                run.skippedCount(),
                run.failedCount(),
                run.indexedDocumentCount(),
                run.indexedChunkCount(),
                run.failedDocumentCount(),
                failures,
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
                run.updatedAt()
        );
    }
}
