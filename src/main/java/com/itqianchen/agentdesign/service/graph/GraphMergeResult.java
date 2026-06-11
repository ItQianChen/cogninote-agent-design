package com.itqianchen.agentdesign.service.graph;

/**
 * merge 阶段结果计数。
 */
public record GraphMergeResult(
        int nodeCount,
        int edgeCount
) {
}
