package com.itqianchen.agentdesign.service.graph;

import java.util.List;

/**
 * 模型返回的单 chunk 抽取 JSON 契约。
 */
public record GraphExtractionPayload(
        List<Node> nodes,
        List<Edge> edges
) {
    public record Node(
            String name,
            String type,
            String description,
            Double confidence,
            String quote
    ) {
    }

    public record Edge(
            String source,
            String target,
            String type,
            String description,
            Double confidence,
            String quote
    ) {
    }
}
