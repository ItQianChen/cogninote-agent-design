package com.itqianchen.agentdesign.mapper.document;

import com.itqianchen.agentdesign.domain.document.FileType;

public record IndexedDocumentRow(
        String documentId,
        String sourcePath,
        String fileName,
        FileType fileType,
        String chunkId,
        int chunkIndex,
        String content,
        String contentHash,
        Integer pageNumber,
        String heading
) {
}
