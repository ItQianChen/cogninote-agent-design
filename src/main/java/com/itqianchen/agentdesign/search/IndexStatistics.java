package com.itqianchen.agentdesign.search;

public record IndexStatistics(
        long parsedDocumentCount,
        long unindexedDocumentCount,
        Long lastIndexedAt
) {
}
