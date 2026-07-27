package com.itqianchen.agentdesign.service.task;

import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;

/** handler 对外暴露的失败终态、稳定错误码和脱敏消息。 */
public record DurableTaskFailure(DurableTaskStatus status, String code, String message) {

    public DurableTaskFailure {
        if (status != DurableTaskStatus.FAILED && status != DurableTaskStatus.INTERRUPTED) {
            throw new IllegalArgumentException("Task failure must end in FAILED or INTERRUPTED");
        }
    }

    public static DurableTaskFailure failed(String code, String message) {
        return new DurableTaskFailure(DurableTaskStatus.FAILED, code, message);
    }

    public static DurableTaskFailure interrupted(String code, String message) {
        return new DurableTaskFailure(DurableTaskStatus.INTERRUPTED, code, message);
    }
}
