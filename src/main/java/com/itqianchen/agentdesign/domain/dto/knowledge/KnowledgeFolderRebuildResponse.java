package com.itqianchen.agentdesign.domain.dto.knowledge;

import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.index.RebuildIndexResponse;
import java.util.List;
import java.util.stream.Stream;

/**
 * Knowledge Folder Rebuild 响应 定义返回给前端的 知识库 响应结构。
 * <p>该结构属于接口契约，调整字段时需要兼容已有调用方。</p>
 */
public record KnowledgeFolderRebuildResponse(
        int scannedCount,
        int parsedCount,
        int skippedCount,
        int failedCount,
        List<DocumentFailureResponse> failures,
        long indexedDocumentCount,
        long indexedChunkCount,
        long failedDocumentCount,
        long durationMs
) {
    /**
     * 合并目录扫描解析和索引重建两段统计。
     *
     * <p>前端只展示一个重建任务结果，因此这里保留两段流程各自的失败计数。</p>
     */
    public static KnowledgeFolderRebuildResponse from(
            IngestDocumentsResponse ingestResponse,
            RebuildIndexResponse rebuildResponse
    ) {
        return new KnowledgeFolderRebuildResponse(
                ingestResponse.scannedCount(),
                ingestResponse.parsedCount(),
                ingestResponse.skippedCount(),
                ingestResponse.failedCount(),
                Stream.concat(ingestResponse.failures().stream(), rebuildResponse.failures().stream()).toList(),
                rebuildResponse.indexedDocumentCount(),
                rebuildResponse.indexedChunkCount(),
                rebuildResponse.failedDocumentCount(),
                rebuildResponse.durationMs()
        );
    }
}
