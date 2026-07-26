package com.itqianchen.agentdesign.domain.dto.system;

import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;

/** 恢复预检和跨重启执行状态。 */
public record RestoreStatusResponse(
        String restoreId,
        RestorePhase phase,
        String message,
        String sourceAppVersion,
        int sourceSchemaVersion,
        long createdAt,
        long updatedAt,
        boolean containsSecrets,
        long documentCount,
        long chatSessionCount,
        long graphNodeCount,
        boolean restartRequired
) {
}
