package com.itqianchen.agentdesign.domain.dto.system;

/** 设置页使用的轻量备份恢复摘要。 */
public record DataProtectionStatusResponse(
        int schemaVersion,
        boolean pendingRestore,
        String lastOperation,
        String lastStatus,
        Long lastCompletedAt
) {
}
