package com.itqianchen.agentdesign.search;

import com.itqianchen.agentdesign.document.FileType;
import java.util.List;

public record IndexedDocument(
        String id,
        String sourcePath,
        String fileName,
        FileType fileType,
        List<IndexedChunk> chunks
) {
}
