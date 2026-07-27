package com.itqianchen.agentdesign.domain.vo.knowledge;

import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;

/** 知识维护任务可跨进程恢复的第一版参数契约。 */
public record MaintenanceTaskPayloadV1(
        KnowledgeFolderRunScopeType scopeType,
        String scopeId,
        KnowledgeFolderRunOperation operation,
        String folderPath,
        boolean recursive,
        Boolean enabled
) {
}
