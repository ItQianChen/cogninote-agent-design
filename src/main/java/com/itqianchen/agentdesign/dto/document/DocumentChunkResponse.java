package com.itqianchen.agentdesign.dto.document;

import com.itqianchen.agentdesign.domain.search.StoredChunk;

/**
 * Document Chunk 响应 定义返回给前端的文档片段详情结构。
 * <p>弹窗预览需要完整 chunk 内容，因此这里不复用搜索摘要字段。</p>
 */
public record DocumentChunkResponse(
        String chunkId,
        String documentId,
        int chunkIndex,
        String fileName,
        String sourcePath,
        String heading,
        Integer pageNumber,
        String content,
        String contentHash
) {
    /**
     * 将持久化片段转换为前端可消费的响应。
     * <p>该映射集中处理字段命名差异，避免控制器泄漏存储对象。</p>
     */
    public static DocumentChunkResponse from(StoredChunk chunk) {
        return new DocumentChunkResponse(
                chunk.chunkId(),
                chunk.documentId(),
                chunk.chunkIndex(),
                chunk.fileName(),
                chunk.sourcePath(),
                chunk.heading(),
                chunk.pageNumber(),
                chunk.content(),
                chunk.contentHash()
        );
    }
}
