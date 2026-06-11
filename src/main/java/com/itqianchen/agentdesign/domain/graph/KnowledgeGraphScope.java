package com.itqianchen.agentdesign.domain.graph;

/**
 * 知识图谱 scope 的规范化表示。
 * <p>数据库查询统一使用这里清洗后的 scopeType/scopeId，避免 NULL 和空字符串混用。</p>
 */
public record KnowledgeGraphScope(
        KnowledgeGraphScopeType scopeType,
        String scopeId,
        String displayName
) {
    public String normalizedScopeId() {
        return scopeType == KnowledgeGraphScopeType.ALL ? null : scopeId;
    }
}
