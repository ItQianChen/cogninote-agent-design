package com.itqianchen.agentdesign.service.task;

import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;

/** handler 完成业务副作用后交给调度器原子持久化的终态和最终进度。 */
public record DurableTaskOutcome(
        DurableTaskStatus status,
        String resultJson,
        long progressCurrent,
        long progressTotal
) {

    public DurableTaskOutcome(DurableTaskStatus status, String resultJson) {
        this(status, resultJson, 1, 1);
    }

    public DurableTaskOutcome {
        if (status != DurableTaskStatus.COMPLETED && status != DurableTaskStatus.COMPLETED_WITH_WARNINGS) {
            throw new IllegalArgumentException("Task outcome must be a successful terminal status");
        }
        if (progressCurrent < 0 || progressTotal < progressCurrent) {
            throw new IllegalArgumentException("Task outcome progress must satisfy 0 <= current <= total");
        }
    }
}
