package com.itqianchen.agentdesign.domain.graph;

/**
 * 单 chunk 抽取缓存状态。
 * <p>失败缓存会在下次 rebuild 自动重试，因此不需要持久化 SKIPPED。</p>
 */
public enum KnowledgeGraphExtractionStatus {
    EXTRACTED,
    FAILED
}
