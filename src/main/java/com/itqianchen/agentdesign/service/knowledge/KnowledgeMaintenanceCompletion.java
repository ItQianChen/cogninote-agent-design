package com.itqianchen.agentdesign.service.knowledge;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import java.util.List;

/**
 * 维护任务执行完成后的统计快照。
 *
 * <p>队列服务只关心任务生命周期，具体导入、同步和重建服务负责返回业务计数；这样可以避免
 * 队列层直接理解每个底层 DTO 的字段差异。</p>
 */
public record KnowledgeMaintenanceCompletion(
        KnowledgeFolderRunStatus status,
        int scannedCount,
        int parsedCount,
        int skippedCount,
        int failedCount,
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        List<DocumentFailureResponse> failures,
        long progressTotal
) {
    public static KnowledgeMaintenanceCompletion simple() {
        return new KnowledgeMaintenanceCompletion(
                KnowledgeFolderRunStatus.COMPLETED,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                1
        );
    }
}
