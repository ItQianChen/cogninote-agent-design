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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 在业务连接池打开前识别并迁移 SQLite schema。
 *
 * <p>现有无版本数据库只接受 0.1.70 基线。任何未知结构都停止启动，避免把不兼容库错误标记成当前版本。</p>
 */
@Component
public class DatabaseMigrationService {

    public static final int CURRENT_SCHEMA_VERSION = 2;
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
    private final AtomicBoolean migrated = new AtomicBoolean();

    public DatabaseMigrationService(
            AppStorageInitializer storageInitializer,
            SQLiteSnapshotService snapshotService,
            PendingRestoreService pendingRestoreService
    ) {
        this.storageInitializer = storageInitializer;
        this.snapshotService = snapshotService;
        this.pendingRestoreService = pendingRestoreService;
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
            Flyway flyway = Flyway.configure()
                    .dataSource(snapshotService.dataSource(databasePath, false))
                    .locations("classpath:db/migration")
                    .baselineVersion("1")
                    .baselineDescription("CogniNote 0.1.70 baseline")
                    .baselineOnMigrate(false)
                    .outOfOrder(false)
                    .cleanDisabled(true)
                    .validateOnMigrate(true)
                    .load();

            boolean versioned = existingDatabase && hasTable(databasePath, "flyway_schema_history");
            if (existingDatabase && !versioned) {
                validateLegacyBaseline(databasePath);
            }
            if (existingDatabase && (!versioned || hasPendingMigrations(flyway))) {
                rollbackSnapshot = createMigrationSnapshot(storage);
            }
            if (existingDatabase && !versioned) {
                flyway.baseline();
            }

            flyway.migrate();
            snapshotService.validate(databasePath);
            appliedRestore.ifPresent(pendingRestoreService::complete);
            migrated.set(true);
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
                snapshotService.replaceDatabase(rollbackSnapshot, databasePath);
                snapshotService.validate(databasePath);
            }
            throw new DatabaseMigrationException("Database migration failed; original data was preserved", ex);
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

    public int currentSchemaVersion() {
        return currentSchemaVersion(storageInitializer.appStorage().databasePath());
    }

    private Path createMigrationSnapshot(AppStorage storage) {
        String name = "pre-migrate-" + SNAPSHOT_TIME.format(Instant.now()) + ".db";
        Path snapshot = storage.internalBackupDir().resolve(name);
        snapshotService.createSnapshot(storage.databasePath(), snapshot);
        return snapshot;
    }

    private void validateLegacyBaseline(Path databasePath) {
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection()) {
            Set<String> tables = queryNames(connection, "SELECT name FROM sqlite_master WHERE type='table'");
            if (!tables.containsAll(BASELINE_TABLES)
                    || tables.contains("model_config")
                    || tables.contains("knowledge_folder_runs_migration")) {
                throw new DatabaseMigrationException("Database is not the supported 0.1.70 baseline");
            }
            for (Map.Entry<String, Set<String>> entry : BASELINE_COLUMNS.entrySet()) {
                Set<String> columns = queryNames(connection, "PRAGMA table_info(" + entry.getKey() + ")", "name");
                if (!columns.containsAll(entry.getValue())) {
                    throw new DatabaseMigrationException("Database baseline is missing required columns");
                }
            }
            Set<String> edgeIndexColumns = queryNames(
                    connection,
                    "PRAGMA index_info(idx_kg_edges_scope_triple)",
                    "name"
            );
            if (!edgeIndexColumns.containsAll(Set.of(
                    "scope_type", "scope_id", "source_node_id", "target_node_id", "relation_type", "display_label"
            ))) {
                throw new DatabaseMigrationException("Database baseline index is incompatible");
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to inspect database baseline", ex);
        }
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
