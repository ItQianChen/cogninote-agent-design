package com.itqianchen.agentdesign.mapper.knowledge;

public record KnowledgeFolderSummaryRow(
        String id,
        String folderPath,
        String displayName,
        boolean recursive,
        boolean enabled,
        Long lastIngestedAt,
        Long lastIndexedAt,
        long createdAt,
        long updatedAt,
        int documentCount,
        int parsedCount,
        int failedCount,
        int chunkCount,
        int unindexedCount
) {
}
