package com.itqianchen.agentdesign.domain.vo.ingestion;

import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParseCheckpoint;
import java.nio.file.Path;

/** 带可选增量检查点的文档解析请求。 */
public record DocumentParseRequest(
        Path path,
        DocumentParseCheckpoint checkpoint
) {
    public DocumentParseRequest {
        if (path == null) {
            throw new IllegalArgumentException("Document parse path is required");
        }
        checkpoint = checkpoint == null ? DocumentParseCheckpoint.none() : checkpoint;
    }

    public static DocumentParseRequest direct(Path path) {
        return new DocumentParseRequest(path, DocumentParseCheckpoint.none());
    }
}
