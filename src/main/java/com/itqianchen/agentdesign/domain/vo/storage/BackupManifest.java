package com.itqianchen.agentdesign.domain.vo.storage;

import java.util.List;

/** `.cogninote-backup` v1 的公开清单契约。 */
public record BackupManifest(
        int formatVersion,
        String appVersion,
        int schemaVersion,
        String createdAt,
        String platform,
        String secretsPolicy,
        List<BackupContent> contents,
        BackupIncludes includes,
        boolean settingsStoredInSqlite
) {
    public record BackupContent(String path, long sizeBytes, String sha256) {
    }

    public record BackupIncludes(
            boolean sqlite,
            boolean lucene,
            boolean originalFiles,
            boolean logs,
            boolean configFiles
    ) {
    }
}
