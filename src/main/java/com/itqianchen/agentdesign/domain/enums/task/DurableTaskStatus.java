package com.itqianchen.agentdesign.domain.enums.task;

/** 跨进程持久化的通用任务状态；枚举名属于 SQLite 和 API 稳定契约。 */
public enum DurableTaskStatus {
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    CANCELLING,
    CANCELLED,
    COMPLETED,
    COMPLETED_WITH_WARNINGS,
    FAILED,
    INTERRUPTED;

    public boolean terminal() {
        return switch (this) {
            case CANCELLED, COMPLETED, COMPLETED_WITH_WARNINGS, FAILED, INTERRUPTED -> true;
            default -> false;
        };
    }
}
