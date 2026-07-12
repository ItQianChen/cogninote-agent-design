package com.itqianchen.agentdesign.domain.dto.index;

import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import java.util.List;

/**
 * Rebuild Index 响应 定义返回给前端的 检索索引 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record RebuildIndexResponse(
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        long durationMs,
        List<DocumentFailureResponse> failures
) {
    public RebuildIndexResponse(
            long indexedDocumentCount,
            long indexedChunkCount,
            long failedDocumentCount,
            long durationMs
    ) {
        this(indexedDocumentCount, indexedChunkCount, failedDocumentCount, durationMs, List.of());
    }
}


