package com.itqianchen.agentdesign.domain.graph;

/**
 * 知识图谱重建范围。
 * <p>ALL 不需要 scopeId；目录和单文档范围必须带明确 ID。</p>
 */
public enum KnowledgeGraphScopeType {
    ALL,
    KNOWLEDGE_FOLDER,
    DOCUMENT
}
