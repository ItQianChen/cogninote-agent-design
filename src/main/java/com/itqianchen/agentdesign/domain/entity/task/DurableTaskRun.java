package com.itqianchen.agentdesign.domain.entity.task;

import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;

/** 通用耐久任务的 SQLite 事实记录。 */
public record DurableTaskRun(
        String id,
        String taskType,
        String queueName,
        String operation,
        DurableTaskStatus status,
        String step,
        int payloadVersion,
        String payloadJson,
        String checkpointJson,
        String resultJson,
        boolean resumable,
        int attempt,
        int maxAttempts,
        String idempotencyKey,
        String retryOfRunId,
        String leaseOwner,
        Long leaseExpiresAt,
        Long heartbeatAt,
        long availableAt,
        long progressCurrent,
        long progressTotal,
        String currentItem,
        String errorCode,
        String errorMessage,
        long queuedAt,
        Long startedAt,
        Long completedAt,
        Long durationMs,
        long createdAt,
        long updatedAt
) {
}
