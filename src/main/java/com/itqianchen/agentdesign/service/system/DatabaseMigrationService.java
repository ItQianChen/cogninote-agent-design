package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 在业务连接池打开前识别并迁移 SQLite schema。
 *
 * <p>无 Flyway 历史的数据库先经过长期保留的结构适配层；未知结构不会被强制 baseline。</p>
 */
@Component
public class DatabaseMigrationService {

    public static final int CURRENT_SCHEMA_VERSION = 4;
    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationService.class);
    private static final DateTimeFormatter SNAPSHOT_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);
    private static final Set<String> BASELINE_TABLES = Set.of(
            "knowledge_folders", "documents", "document_ocr_checkpoints",
            "document_ocr_checkpoint_pages", "chunks", "model_configs", "chat_sessions",
            "chat_messages", "app_settings", "knowledge_folder_runs", "knowledge_graph_runs",
            "knowledge_graph_chunk_extractions", "knowledge_graph_nodes", "knowledge_graph_edges",
            "knowledge_graph_evidence", "knowledge_graph_views"
    );
    private static final Map<String, Set<String>> BASELINE_COLUMNS = Map.of(
            "documents", Set.of("last_failure_stage", "last_failure_context_json", "last_failed_at"),
            "model_configs", Set.of("embedding_requests_per_minute", "embedding_tokens_per_minute",
                    "embedding_batch_size", "context_window_tokens"),
            "chat_messages", Set.of("agent_type", "references_json"),
            "knowledge_folder_runs", Set.of("phase", "queued_at", "error_stage", "error_code", "error_detail"),
            "knowledge_graph_edges", Set.of("display_label")
    );

    private final AppStorageInitializer storageInitializer;
    private final SQLiteSnapshotService snapshotService;
    private final PendingRestoreService pendingRestoreService;
    private final LegacySchemaInspector schemaInspector;
    private final LegacySchemaAdapter schemaAdapter;
    private final AtomicBoolean migrated = new AtomicBoolean();
    private final AtomicBoolean recoveryMode = new AtomicBoolean();
    private volatile Path businessDatabasePath;
    private final AtomicReference<MigrationInspectionResult> inspection =
            new AtomicReference<>(MigrationInspectionResult.initial(CURRENT_SCHEMA_VERSION));

    @Autowired
    public DatabaseMigrationService(
            AppStorageInitializer storageInitializer,
            SQLiteSnapshotService snapshotService,
            PendingRestoreService pendingRestoreService,
            LegacySchemaInspector schemaInspector,
            LegacySchemaAdapter schemaAdapter
    ) {
        this.storageInitializer = storageInitializer;
        this.snapshotService = snapshotService;
        this.pendingRestoreService = pendingRestoreService;
        this.schemaInspector = schemaInspector;
        this.schemaAdapter = schemaAdapter;
    }

    /** 保留测试和离线工具使用的三参数构造入口。 */
    public DatabaseMigrationService(
            AppStorageInitializer storageInitializer,
            SQLiteSnapshotService snapshotService,
            PendingRestoreService pendingRestoreService
    ) {
        this(storageInitializer, snapshotService, pendingRestoreService,
                new LegacySchemaInspector(snapshotService), new LegacySchemaAdapter(snapshotService));
    }

    /**
     * 幂等执行启动迁移，并在任何修改前保存可回滚快照。
     */
    public synchronized void migrateBeforeConnectionPool() {
        if (migrated.get()) {
            return;
        }
        storageInitializer.ensureInitialized();
        AppStorage storage = storageInitializer.appStorage();
        Path databasePath = storage.databasePath();
        Optional<PendingRestoreService.AppliedRestore> appliedRestore = Optional.empty();
        Path rollbackSnapshot = null;

        try {
            // 恢复交换和 schema 迁移共享同一异常边界，任一失败都必须进入统一回滚路径。
            appliedRestore = pendingRestoreService.applyBeforeMigration();
            boolean existingDatabase = isNonEmptyFile(databasePath);
            LegacySchemaInspector.Inspection detected = schemaInspector.inspect(databasePath);
            inspection.set(new MigrationInspectionResult(
                    "NORMAL",
                    existingDatabase ? "MIGRATION_REQUIRED" : "READY",
                    detected.version(), CURRENT_SCHEMA_VERSION, detected.family(), List.of(), null, null
            ));
            Flyway flyway = Flyway.configure()
                    .dataSource(snapshotService.dataSource(databasePath, false))
                    .locations("classpath:db/migration")
                    .baselineVersion("1")
                    .baselineDescription("CogniNote initial schema baseline")
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .load();

            boolean versioned = existingDatabase && hasTable(databasePath, "flyway_schema_history");
            if (versioned && detected.version() > CURRENT_SCHEMA_VERSION) {
                throw new DatabaseMigrationException("Database schema is newer than this application");
            }
            if (existingDatabase && !versioned) {
                if (detected.family() == SchemaFamily.UNKNOWN) {
                    throw new DatabaseMigrationException("Unsupported database schema; original data was preserved");
                }
                schemaAdapter.adapt(databasePath, detected);
                validateLegacyCore(databasePath);
            }
            if (existingDatabase && (!versioned || hasPendingMigrations(flyway))) {
                rollbackSnapshot = createMigrationSnapshot(storage);
            }
            List<String> pendingMigrations = pendingMigrationVersions(flyway);
            if (existingDatabase && !versioned) {
                flyway.baseline();
            }

            flyway.migrate();
            snapshotService.validate(databasePath);
            if (appliedRestore.isPresent()) {
                interruptRestoredActiveTasks(databasePath);
            }
            appliedRestore.ifPresent(pendingRestoreService::complete);
            migrated.set(true);
            businessDatabasePath = databasePath;
            inspection.set(new MigrationInspectionResult(
                    "NORMAL", "READY", currentSchemaVersion(databasePath), CURRENT_SCHEMA_VERSION,
                    detected.family(), pendingMigrations, null, null
            ));
            pruneInternalSnapshots(storage.internalBackupDir());
            log.info("database_migration_ready schemaVersion={}", currentSchemaVersion(databasePath));
        } catch (RuntimeException ex) {
            if (appliedRestore.isPresent()) {
                pendingRestoreService.rollback(appliedRestore.get());
                migrateRolledBackDatabase(databasePath);
                migrated.set(true);
                return;
            }
            if (rollbackSnapshot != null) {
                try {
                    snapshotService.replaceDatabase(rollbackSnapshot, databasePath);
                    snapshotService.validate(databasePath);
                } catch (RuntimeException rollbackFailure) {
                    ex.addSuppressed(rollbackFailure);
                    log.error("database_migration_rollback_failed", rollbackFailure);
                }
            }
            activateRecoveryDatabase(storage);
            inspection.set(new MigrationInspectionResult(
                    "MIGRATION_RECOVERY", "UNSUPPORTED", safeDetectedVersion(databasePath), CURRENT_SCHEMA_VERSION,
                    safeDetectedFamily(databasePath), List.of(), "MIGRATION_FAILED", ex.getMessage()
            ));
            log.error("database_migration_recovery_mode", ex);
            return;
        }
    }

    private void migrateRolledBackDatabase(Path databasePath) {
        Flyway.configure()
                .dataSource(snapshotService.dataSource(databasePath, false))
                .locations("classpath:db/migration")
                .baselineOnMigrate(false)
                .outOfOrder(false)
                .cleanDisabled(true)
                .validateOnMigrate(true)
                .load()
                .migrate();
        snapshotService.validate(databasePath);
    }

    private void interruptRestoredActiveTasks(Path databasePath) {
        long now = System.currentTimeMillis();
        try (Connection connection = snapshotService.dataSource(databasePath, false).getConnection();
             var statement = connection.prepareStatement("""
                     UPDATE durable_task_runs
                     SET status = 'INTERRUPTED',
                         step = 'INTERRUPTED',
                         completed_at = ?,
                         duration_ms = CASE WHEN started_at IS NULL THEN 0 ELSE MAX(0, ? - started_at) END,
                         lease_owner = NULL,
                         lease_expires_at = NULL,
                         heartbeat_at = NULL,
                         error_code = 'RESTORE_BOUNDARY',
                         error_message = '备份恢复不会自动重放备份时仍在活动的任务，请手动重试。',
                         updated_at = ?
                     WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCELLING')
                     """)) {
            statement.setLong(1, now);
            statement.setLong(2, now);
            statement.setLong(3, now);
            int interrupted = statement.executeUpdate();
            if (interrupted > 0) {
                log.info("restored_durable_tasks_interrupted count={}", interrupted);
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to isolate restored durable tasks", ex);
        }
    }

    public int currentSchemaVersion() {
        return currentSchemaVersion(businessDatabasePath());
    }

    public MigrationInspectionResult inspection() {
        return inspection.get();
    }

    public boolean isRecoveryMode() {
        return recoveryMode.get();
    }

    public Path businessDatabasePath() {
        if (businessDatabasePath != null) return businessDatabasePath;
        return storageInitializer.appStorage().databasePath();
    }

    /** 重试只针对原始业务库；成功后需由桌面端重启以重新创建业务连接池。 */
    public synchronized void retryMigration() {
        migrated.set(false);
        recoveryMode.set(false);
        businessDatabasePath = storageInitializer.appStorage().databasePath();
        migrateBeforeConnectionPool();
    }

    private void activateRecoveryDatabase(AppStorage storage) {
        Path recoveryDatabase = storage.dataDir().resolve("migration-recovery.db");
        try {
            Flyway.configure()
                    .dataSource(snapshotService.dataSource(recoveryDatabase, false))
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            businessDatabasePath = recoveryDatabase;
            recoveryMode.set(true);
        } catch (RuntimeException recoveryFailure) {
            throw new DatabaseMigrationException("Failed to initialize migration recovery database", recoveryFailure);
        }
    }

    /** 只校验业务核心结构；历史附加表允许继续存在，不参与兼容判断。 */
    private void validateLegacyCore(Path databasePath) {
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection()) {
            Set<String> tables = queryNames(connection, "SELECT name FROM sqlite_master WHERE type='table'");
            if (!tables.containsAll(BASELINE_TABLES)) {
                throw new DatabaseMigrationException("Legacy database is missing required core tables");
            }
            for (Map.Entry<String, Set<String>> entry : BASELINE_COLUMNS.entrySet()) {
                Set<String> columns = queryNames(connection, "PRAGMA table_info(" + entry.getKey() + ")", "name");
                if (!columns.containsAll(entry.getValue())) {
                    throw new DatabaseMigrationException("Legacy database is missing required core columns");
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to validate legacy core schema", ex);
        }
    }

    private int safeDetectedVersion(Path databasePath) {
        try { return schemaInspector.inspect(databasePath).version(); }
        catch (RuntimeException ignored) { return 0; }
    }

    private SchemaFamily safeDetectedFamily(Path databasePath) {
        try { return schemaInspector.inspect(databasePath).family(); }
        catch (RuntimeException ignored) { return SchemaFamily.UNKNOWN; }
    }

    private Path createMigrationSnapshot(AppStorage storage) {
        String name = "pre-migrate-" + SNAPSHOT_TIME.format(Instant.now()) + ".db";
        Path snapshot = storage.internalBackupDir().resolve(name);
        snapshotService.createSnapshot(storage.databasePath(), snapshot);
        return snapshot;
    }

    private boolean hasTable(Path databasePath, String tableName) {
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?"
             )) {
            statement.setString(1, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getInt(1) > 0;
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to inspect migration history", ex);
        }
    }

    private int currentSchemaVersion(Path databasePath) {
        if (!isNonEmptyFile(databasePath) || !hasTable(databasePath, "flyway_schema_history")) {
            return 0;
        }
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT version
                     FROM flyway_schema_history
                     WHERE success = 1 AND version IS NOT NULL
                     ORDER BY installed_rank DESC
                     LIMIT 1
                     """)) {
            return resultSet.next() ? Integer.parseInt(resultSet.getString(1)) : 0;
        } catch (SQLException | NumberFormatException ex) {
            throw new DatabaseMigrationException("Failed to read database schema version", ex);
        }
    }

    private static boolean hasPendingMigrations(Flyway flyway) {
        MigrationInfo[] pending = flyway.info().pending();
        return pending != null && pending.length > 0;
    }

    private static List<String> pendingMigrationVersions(Flyway flyway) {
        MigrationInfo[] pending = flyway.info().pending();
        if (pending == null) return List.of();
        return java.util.Arrays.stream(pending)
                .map(MigrationInfo::getVersion)
                .filter(java.util.Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    private static Set<String> queryNames(Connection connection, String sql) throws SQLException {
        return queryNames(connection, sql, "name");
    }

    private static Set<String> queryNames(Connection connection, String sql, String column) throws SQLException {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                names.add(resultSet.getString(column));
            }
        }
        return Set.copyOf(names);
    }

    private static boolean isNonEmptyFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to inspect SQLite file", ex);
        }
    }

    private static void pruneInternalSnapshots(Path directory) {
        try (var files = Files.list(directory)) {
            java.util.List<Path> snapshots = files
                    .filter(path -> path.getFileName().toString().startsWith("pre-migrate-"))
                    .sorted(Comparator.comparingLong(DatabaseMigrationService::lastModified).reversed())
                    .toList();
            for (int index = 3; index < snapshots.size(); index++) {
                Files.deleteIfExists(snapshots.get(index));
            }
        } catch (IOException ex) {
            log.warn("database_snapshot_retention_failed", ex);
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }
}
