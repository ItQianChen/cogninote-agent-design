package com.itqianchen.agentdesign.domain.dto.system;

/** 已生成并等待桌面壳保存的便携备份。 */
public record BackupCreateResponse(
        String backupId,
        String suggestedFileName,
        long sizeBytes,
        String sha256,
        int schemaVersion,
        boolean containsSecrets
) {
}
