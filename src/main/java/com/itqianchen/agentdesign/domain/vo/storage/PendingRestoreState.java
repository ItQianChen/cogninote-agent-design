package com.itqianchen.agentdesign.domain.vo.storage;

import com.itqianchen.agentdesign.domain.dto.system.RestoreStatusResponse;
import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;

/**
 * 恢复流程保存在应用数据目录中的最小状态。
 *
 * <p>数据库和工作目录均由 restoreId 推导，状态文件不保存用户选择的原始路径。</p>
 */
public record PendingRestoreState(
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
        long graphNodeCount
) {
    public PendingRestoreState withPhase(RestorePhase nextPhase, String nextMessage, long now) {
        return new PendingRestoreState(
                restoreId, nextPhase, nextMessage, sourceAppVersion, sourceSchemaVersion,
                createdAt, now, containsSecrets, documentCount, chatSessionCount, graphNodeCount
        );
    }

    public RestoreStatusResponse toResponse() {
        return new RestoreStatusResponse(
                restoreId, phase, message, sourceAppVersion, sourceSchemaVersion,
                createdAt, updatedAt, containsSecrets, documentCount, chatSessionCount, graphNodeCount,
                phase == RestorePhase.SCHEDULED
        );
    }
}
