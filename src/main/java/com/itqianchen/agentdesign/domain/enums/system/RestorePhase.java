package com.itqianchen.agentdesign.domain.enums.system;

/** 恢复任务跨进程持久化的稳定阶段。 */
public enum RestorePhase {
    PREFLIGHTED,
    DISCARDED,
    SCHEDULED,
    SWAPPING,
    VALIDATING,
    REINDEXING,
    COMPLETED,
    ROLLED_BACK,
    REINDEX_FAILED
}
