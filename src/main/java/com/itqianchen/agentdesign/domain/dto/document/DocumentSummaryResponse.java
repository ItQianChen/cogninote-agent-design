package com.itqianchen.agentdesign.domain.dto.document;

import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;

/**
 * 文档管理列表的摘要响应。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record DocumentSummaryResponse(
        String id,
        String knowledgeFolderId,
        String sourcePath,
        String fileName,
        String fileType,
        long fileSize,
        long lastModified,
        String contentHash,
        String status,
        Long indexedAt,
        long createdAt,
        long updatedAt,
        int chunkCount,
        DocumentFailureResponse lastFailure
) {
    /**
     * 构造前端文档表格使用的摘要。
     *
     * <p>状态和文件类型暴露为枚举名字符串，和前端筛选项保持同一套取值。</p>
     */
    public static DocumentSummaryResponse from(KnowledgeDocument document) {
        return from(document, null);
    }

    /**
     * 构造包含最近失败诊断的文档摘要。
     *
     * @param document 文档记录
     * @param lastFailure 最近一次处理失败；没有失败时为空
     * @return 文档摘要
     */
    public static DocumentSummaryResponse from(
            KnowledgeDocument document,
            DocumentFailureResponse lastFailure
    ) {
        return new DocumentSummaryResponse(
                document.id(),
                document.knowledgeFolderId(),
                document.sourcePath(),
                document.fileName(),
                document.fileType().name(),
                document.fileSize(),
                document.lastModified(),
                document.contentHash(),
                document.status().name(),
                document.indexedAt(),
                document.createdAt(),
                document.updatedAt(),
                document.chunkCount(),
                lastFailure
        );
    }
}
