package com.itqianchen.agentdesign.domain.graph;

/**
 * 知识图谱后台任务状态。
 * <p>状态值直接持久化到 SQLite，调整枚举名需要兼容旧数据。</p>
 */
public enum KnowledgeGraphRunStatus {
    QUEUED,
    RUNNING,
    CANCELLED,
    COMPLETED,
    FAILED
}
