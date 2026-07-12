package com.itqianchen.agentdesign.domain.entity.document;


import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
/**
 * 本地知识文档在 SQLite 中的事实记录。
 *
 * <p>sourcePath、lastModified 和 contentHash 用于判断是否需要重新解析；indexedAt 表示当前 chunk
 * 版本是否已经写入 Lucene。删除该记录只删除应用内元数据，不删除用户本地文件。</p>
 */
public record KnowledgeDocument(
        String id,
        String knowledgeFolderId,
        String sourcePath,
        String fileName,
        FileType fileType,
        long fileSize,
        long lastModified,
        String contentHash,
        DocumentStatus status,
        Long indexedAt,
        long createdAt,
        long updatedAt,
        int chunkCount,
        String lastFailureStage,
        String lastFailureCode,
        String lastFailureMessage,
        String lastFailureDetail,
        String lastFailureContextJson,
        Long lastFailedAt
) {
    /**
     * 兼容不携带失败诊断的既有构造调用。
     */
    public KnowledgeDocument(
            String id,
            String knowledgeFolderId,
            String sourcePath,
            String fileName,
            FileType fileType,
            long fileSize,
            long lastModified,
            String contentHash,
            DocumentStatus status,
            Long indexedAt,
            long createdAt,
            long updatedAt,
            int chunkCount
    ) {
        this(
                id,
                knowledgeFolderId,
                sourcePath,
                fileName,
                fileType,
                fileSize,
                lastModified,
                contentHash,
                status,
                indexedAt,
                createdAt,
                updatedAt,
                chunkCount,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    public KnowledgeDocument(
            String id,
            String sourcePath,
            String fileName,
            FileType fileType,
            long fileSize,
            long lastModified,
            String contentHash,
            DocumentStatus status,
            Long indexedAt,
            long createdAt,
            long updatedAt,
            int chunkCount
    ) {
        this(
                id,
                null,
                sourcePath,
                fileName,
                fileType,
                fileSize,
                lastModified,
                contentHash,
                status,
                indexedAt,
                createdAt,
                updatedAt,
                chunkCount,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 判断最近一次处理是否留下失败诊断。
     *
     * @return 存在稳定错误码或用户消息时为 true
     */
    public boolean hasLastFailure() {
        return (lastFailureCode != null && !lastFailureCode.isBlank())
                || (lastFailureMessage != null && !lastFailureMessage.isBlank());
    }
}


