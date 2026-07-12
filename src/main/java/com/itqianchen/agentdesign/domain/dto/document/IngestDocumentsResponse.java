package com.itqianchen.agentdesign.domain.dto.document;

import java.util.List;

/**
 * Ingest Documents 响应 定义返回给前端的 文档管理 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record IngestDocumentsResponse(
        int scannedCount,
        int parsedCount,
        int skippedCount,
        int failedCount,
        List<DocumentFailureResponse> failures
) {
}


