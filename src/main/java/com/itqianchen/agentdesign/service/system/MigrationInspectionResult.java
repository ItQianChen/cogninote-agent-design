package com.itqianchen.agentdesign.service.system;

import java.util.List;

/** 迁移诊断快照，供启动状态和恢复页面共同使用。 */
public record MigrationInspectionResult(
        String mode,
        String databaseStatus,
        int detectedSchemaVersion,
        int latestSchemaVersion,
        SchemaFamily detectedSchemaFamily,
        List<String> pendingMigrations,
        String migrationErrorCode,
        String migrationErrorMessage
) {
    public static MigrationInspectionResult initial(int latestSchemaVersion) {
        return new MigrationInspectionResult(
                "NORMAL", "READY", 0, latestSchemaVersion, SchemaFamily.EMPTY, List.of(), null, null
        );
    }
}
