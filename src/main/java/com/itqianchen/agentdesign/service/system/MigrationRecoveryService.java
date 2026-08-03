package com.itqianchen.agentdesign.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.system.MigrationRecoveryFileResponse;
import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 在迁移失败时提供不依赖业务 DataSource 的诊断和原始备份能力。 */
@Service
public class MigrationRecoveryService {
    private final DatabaseMigrationService migrationService;
    private final AppStorageInitializer storageInitializer;
    private final SQLiteSnapshotService snapshotService;
    private final ObjectMapper objectMapper;

    public MigrationRecoveryService(
            DatabaseMigrationService migrationService,
            AppStorageInitializer storageInitializer,
            SQLiteSnapshotService snapshotService,
            ObjectMapper objectMapper
    ) {
        this.migrationService = migrationService;
        this.storageInitializer = storageInitializer;
        this.snapshotService = snapshotService;
        this.objectMapper = objectMapper;
    }

    public MigrationInspectionResult status() {
        return migrationService.inspection();
    }

    public synchronized MigrationRecoveryFileResponse backup() {
        AppStorage storage = storageInitializer.appStorage();
        Path target = storage.backupExportDir().resolve("migration-original-"
                + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".db");
        try {
            try {
                snapshotService.createSnapshot(storage.databasePath(), target);
            } catch (RuntimeException snapshotFailure) {
                // 损坏库无法通过 SQLite backup API 读取时，仍保留原始字节供人工恢复。
                Files.copy(storage.databasePath(), target);
            }
            return new MigrationRecoveryFileResponse(target.toString(), "ORIGINAL_DATABASE", Files.size(target));
        } catch (IOException | RuntimeException ex) {
            throw new DatabaseMigrationException("Failed to create migration recovery backup", ex);
        }
    }

    public synchronized MigrationRecoveryFileResponse exportDiagnostics() {
        AppStorage storage = storageInitializer.appStorage();
        Path target = storage.backupExportDir().resolve("migration-diagnostics-"
                + Instant.now().toEpochMilli() + "-" + UUID.randomUUID() + ".json");
        try {
            Files.writeString(target, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "inspection", migrationService.inspection(),
                    "databasePath", storage.databasePath().toString(),
                    "recoveryDatabasePath", migrationService.businessDatabasePath().toString()
            )));
            return new MigrationRecoveryFileResponse(target.toString(), "DIAGNOSTICS", Files.size(target));
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to export migration diagnostics", ex);
        }
    }

    public MigrationInspectionResult retry() {
        migrationService.retryMigration();
        return migrationService.inspection();
    }
}
