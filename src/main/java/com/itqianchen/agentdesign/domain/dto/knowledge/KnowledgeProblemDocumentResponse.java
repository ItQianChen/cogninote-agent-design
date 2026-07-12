package com.itqianchen.agentdesign.domain.dto.knowledge;

import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.vo.ingestion.ScannedDocumentFile;

/**
 * 健康抽屉中展示的问题文档。
 */
public record KnowledgeProblemDocumentResponse(
        String documentId,
        String sourcePath,
        String fileName,
        String message,
        long updatedAt,
        DocumentFailureResponse lastFailure
) {
    /**
     * 从文档记录构造问题文档响应。
     *
     * @param document 文档记录
     * @param message 问题说明
     * @return 问题文档响应
     */
    public static KnowledgeProblemDocumentResponse from(KnowledgeDocument document, String message) {
        return new KnowledgeProblemDocumentResponse(
                document.id(),
                document.sourcePath(),
                document.fileName(),
                message,
                document.updatedAt(),
                null
        );
    }

    public static KnowledgeProblemDocumentResponse from(
            KnowledgeDocument document,
            String message,
            DocumentFailureResponse lastFailure
    ) {
        return new KnowledgeProblemDocumentResponse(
                document.id(),
                document.sourcePath(),
                document.fileName(),
                message,
                document.updatedAt(),
                lastFailure
        );
    }

    /**
     * 从本地扫描文件构造问题文档响应。
     *
     * @param file 本地文件快照
     * @param message 问题说明
     * @return 问题文档响应
     */
    public static KnowledgeProblemDocumentResponse from(ScannedDocumentFile file, String message) {
        return new KnowledgeProblemDocumentResponse(
                file.documentId(),
                file.sourcePath(),
                file.fileName(),
                message,
                file.lastModified(),
                null
        );
    }
}
