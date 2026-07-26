package com.itqianchen.agentdesign.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.system.BackupCreateResponse;
import com.itqianchen.agentdesign.domain.dto.system.DataProtectionStatusResponse;
import com.itqianchen.agentdesign.domain.dto.system.RestoreScheduleResponse;
import com.itqianchen.agentdesign.domain.dto.system.RestoreStatusResponse;
import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException.Reason;
import com.itqianchen.agentdesign.domain.vo.storage.BackupManifest;
import com.itqianchen.agentdesign.domain.vo.storage.BackupManifest.BackupContent;
import com.itqianchen.agentdesign.domain.vo.storage.BackupManifest.BackupIncludes;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 创建便携备份并把不可信恢复包收敛为已验证的受控工作目录。 */
@Service
public class DataProtectionService {

    private static final int FORMAT_VERSION = 1;
    private static final long MAX_MANIFEST_BYTES = 256L * 1024;
    private static final long MAX_DATABASE_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long MIN_FREE_SPACE_MARGIN = 512L * 1024 * 1024;
    private static final String MANIFEST_PATH = "manifest.json";
    private static final String DATABASE_PATH = "data/cogninote.db";
    private static final Set<String> PACKAGE_ENTRIES = Set.of(MANIFEST_PATH, DATABASE_PATH);
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "flyway_schema_history", "knowledge_folders", "documents", "document_ocr_checkpoints",
            "document_ocr_checkpoint_pages", "chunks", "model_configs", "chat_sessions", "chat_messages",
            "app_settings", "knowledge_folder_runs", "knowledge_graph_runs",
            "knowledge_graph_chunk_extractions", "knowledge_graph_nodes", "knowledge_graph_edges",
            "knowledge_graph_evidence", "knowledge_graph_views", "data_protection_events"
    );
    private static final Set<String> REQUIRED_BASE_TABLES = Set.of(
            "flyway_schema_history", "knowledge_folders", "documents", "document_ocr_checkpoints",
            "document_ocr_checkpoint_pages", "chunks", "model_configs", "chat_sessions", "chat_messages",
            "app_settings", "knowledge_folder_runs", "knowledge_graph_runs",
            "knowledge_graph_chunk_extractions", "knowledge_graph_nodes", "knowledge_graph_edges",
            "knowledge_graph_evidence", "knowledge_graph_views"
    );
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneOffset.UTC);

    private final DataProtectionFileStore fileStore;
    private final SQLiteSnapshotService snapshotService;
    private final DatabaseMigrationService migrationService;
    private final SystemStatusService systemStatusService;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public DataProtectionService(
            DataProtectionFileStore fileStore,
            SQLiteSnapshotService snapshotService,
            DatabaseMigrationService migrationService,
            SystemStatusService systemStatusService,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.fileStore = fileStore;
        this.snapshotService = snapshotService;
        this.migrationService = migrationService;
        this.systemStatusService = systemStatusService;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建保留明文 API Key 的完整 SQLite 便携备份。
     */
    public synchronized BackupCreateResponse createBackup() {
        fileStore.cleanupStaleTransientFiles();
        String backupId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String suggestedName = "CogniNote-" + FILE_TIME.format(now) + ".cogninote-backup";
        Path packagePath = fileStore.exportPath(backupId);
        Path snapshotPath = packagePath.resolveSibling(backupId + ".db.tmp");
        int schemaVersion = migrationService.currentSchemaVersion();

        try {
            snapshotService.createSnapshot(fileStore.storage().databasePath(), snapshotPath);
            snapshotService.validate(snapshotPath);
            long databaseSize = Files.size(snapshotPath);
            String databaseHash = sha256(snapshotPath);
            BackupManifest manifest = new BackupManifest(
                    FORMAT_VERSION,
                    systemStatusService.status().version(),
                    schemaVersion,
                    now.toString(),
                    System.getProperty("os.name", "unknown"),
                    "PLAINTEXT",
                    List.of(new BackupContent(DATABASE_PATH, databaseSize, databaseHash)),
                    new BackupIncludes(true, false, false, false, false),
                    true
            );
            writePackage(packagePath, snapshotPath, manifest);
            String packageHash = sha256(packagePath);
            long packageSize = Files.size(packagePath);
            recordEvent("BACKUP", "COMPLETED", suggestedName, packageHash, schemaVersion, schemaVersion, null);
            return new BackupCreateResponse(
                    backupId, suggestedName, packageSize, packageHash, schemaVersion, true
            );
        } catch (IOException | RuntimeException ex) {
            recordEvent("BACKUP", "FAILED", suggestedName, null, schemaVersion, schemaVersion,
                    "Backup package could not be created");
            throw new DataProtectionException(Reason.IO_FAILURE, "Backup package could not be created", ex);
        } finally {
            deleteQuietly(snapshotPath);
        }
    }

    /**
     * 验证 Tauri 已放入受控 inbox 的备份包并提取到恢复工作目录。
     */
    public synchronized RestoreStatusResponse preflight(String importId) {
        Path packagePath = fileStore.inboxPath(importId);
        if (!Files.isRegularFile(packagePath)) {
            throw new DataProtectionException(Reason.NOT_FOUND, "Backup package was not found");
        }

        String restoreId = UUID.randomUUID().toString();
        Path workDirectory = fileStore.restoreWorkDir(restoreId);
        Path restoredDatabase = fileStore.restoredDatabase(restoreId);
        try {
            Files.createDirectories(workDirectory);
            BackupManifest manifest = inspectPackage(packagePath);
            BackupContent databaseContent = validateManifest(manifest);
            validateDiskSpace(workDirectory, databaseContent.sizeBytes());
            extractDatabase(packagePath, restoredDatabase, databaseContent);
            snapshotService.validate(restoredDatabase);
            validateSupportedSchema(restoredDatabase, manifest.schemaVersion());
            Counts counts = readCounts(restoredDatabase);
            long now = System.currentTimeMillis();
            PendingRestoreState state = new PendingRestoreState(
                    restoreId,
                    RestorePhase.PREFLIGHTED,
                    "恢复包校验通过，确认后将重启应用。",
                    manifest.appVersion(),
                    manifest.schemaVersion(),
                    now,
                    now,
                    true,
                    counts.documents(),
                    counts.chatSessions(),
                    counts.graphNodes()
            );
            fileStore.writeState(state);
            return state.toResponse();
        } catch (DataProtectionException ex) {
            deleteQuietly(restoredDatabase);
            throw ex;
        } catch (IOException | RuntimeException ex) {
            deleteQuietly(restoredDatabase);
            throw new DataProtectionException(Reason.INVALID_PACKAGE, "Backup package validation failed", ex);
        } finally {
            // inbox 中的原包含明文密钥，预检完成后只保留已验证工作副本。
            deleteQuietly(packagePath);
        }
    }

    public synchronized RestoreScheduleResponse scheduleRestore(String restoreId) {
        PendingRestoreState current = fileStore.readState(restoreId);
        if (current.phase() != RestorePhase.PREFLIGHTED) {
            throw new DataProtectionException(Reason.CONFLICT, "Restore is not ready to be scheduled");
        }
        long now = System.currentTimeMillis();
        PendingRestoreState scheduled = current.withPhase(
                RestorePhase.SCHEDULED,
                "恢复已安排，将在应用重启时执行。",
                now
        );
        fileStore.schedule(scheduled);
        recordEvent("RESTORE", "SCHEDULED", null, null, current.sourceSchemaVersion(),
                DatabaseMigrationService.CURRENT_SCHEMA_VERSION, null);
        return new RestoreScheduleResponse(restoreId, true);
    }

    /** 放弃尚未调度的恢复，并立即清除包含明文密钥的工作副本。 */
    public synchronized RestoreStatusResponse discardRestore(String restoreId) {
        PendingRestoreState current = fileStore.readState(restoreId);
        if (current.phase() != RestorePhase.PREFLIGHTED && current.phase() != RestorePhase.DISCARDED) {
            throw new DataProtectionException(Reason.CONFLICT, "Only a preflighted restore can be discarded");
        }
        PendingRestoreState discarded = current.phase() == RestorePhase.DISCARDED
                ? current
                : current.withPhase(
                        RestorePhase.DISCARDED,
                        "恢复已取消，敏感工作副本已清理。",
                        System.currentTimeMillis()
                );
        fileStore.writeState(discarded);
        fileStore.cleanupRestoreArtifacts(discarded.restoreId());
        return discarded.toResponse();
    }

    public RestoreStatusResponse restoreStatus(String restoreId) {
        return fileStore.readState(restoreId).toResponse();
    }

    public DataProtectionStatusResponse status() {
        fileStore.cleanupStaleTransientFiles();
        PendingRestoreState latestRestore = fileStore.latestState().orElse(null);
        EventSummary event = jdbcTemplate.query("""
                        SELECT operation, status, completed_at
                        FROM data_protection_events
                        ORDER BY started_at DESC
                        LIMIT 1
                        """,
                resultSet -> resultSet.next()
                        ? new EventSummary(
                                resultSet.getString("operation"),
                                resultSet.getString("status"),
                                nullableLong(resultSet, "completed_at")
                        )
                        : null
        );
        boolean preferRestoreState = shouldPreferRestoreState(latestRestore, event);
        String lastOperation = preferRestoreState ? "RESTORE" : event == null ? null : event.operation();
        String lastStatus = preferRestoreState
                ? latestRestore.phase().name()
                : event == null ? null : event.status();
        Long lastCompletedAt = preferRestoreState
                ? Long.valueOf(latestRestore.updatedAt())
                : event == null ? null : event.completedAt();
        return new DataProtectionStatusResponse(
                migrationService.currentSchemaVersion(),
                fileStore.pendingRestore().isPresent(),
                lastOperation,
                lastStatus,
                lastCompletedAt
        );
    }

    void recordRestoreResult(PendingRestoreState state, String status, String message) {
        recordEvent(
                "RESTORE", status, null, null, state.sourceSchemaVersion(),
                DatabaseMigrationService.CURRENT_SCHEMA_VERSION, message
        );
    }

    private BackupManifest inspectPackage(Path packagePath) throws IOException {
        try (ZipFile zipFile = new ZipFile(packagePath.toFile())) {
            Set<String> names = zipFile.stream().map(ZipEntry::getName).collect(java.util.stream.Collectors.toSet());
            if (zipFile.size() != PACKAGE_ENTRIES.size() || !names.equals(PACKAGE_ENTRIES)) {
                throw invalidPackage("Backup package contains unexpected entries");
            }
            ZipEntry manifestEntry = zipFile.getEntry(MANIFEST_PATH);
            if (manifestEntry.isDirectory() || manifestEntry.getSize() > MAX_MANIFEST_BYTES) {
                throw invalidPackage("Backup manifest is too large");
            }
            try (InputStream input = zipFile.getInputStream(manifestEntry)) {
                byte[] bytes = input.readNBytes((int) MAX_MANIFEST_BYTES + 1);
                if (bytes.length > MAX_MANIFEST_BYTES) {
                    throw invalidPackage("Backup manifest is too large");
                }
                return objectMapper.readValue(bytes, BackupManifest.class);
            }
        }
    }

    private BackupContent validateManifest(BackupManifest manifest) {
        if (manifest.formatVersion() != FORMAT_VERSION
                || manifest.schemaVersion() < 1
                || manifest.schemaVersion() > DatabaseMigrationService.CURRENT_SCHEMA_VERSION
                || !"PLAINTEXT".equals(manifest.secretsPolicy())
                || manifest.includes() == null
                || !manifest.includes().sqlite()
                || manifest.includes().lucene()
                || manifest.includes().originalFiles()
                || manifest.includes().logs()
                || manifest.includes().configFiles()
                || !manifest.settingsStoredInSqlite()
                || manifest.appVersion() == null
                || manifest.appVersion().isBlank()
                || manifest.contents() == null
                || manifest.contents().size() != 1) {
            throw invalidPackage("Backup manifest is incompatible with this application");
        }
        BackupContent content = manifest.contents().getFirst();
        if (!DATABASE_PATH.equals(content.path())
                || content.sizeBytes() <= 0
                || content.sizeBytes() > MAX_DATABASE_BYTES
                || content.sha256() == null
                || !content.sha256().matches("^[0-9a-f]{64}$")) {
            throw invalidPackage("Backup database metadata is invalid");
        }
        return content;
    }

    private void extractDatabase(Path packagePath, Path target, BackupContent expected) throws IOException {
        try (ZipFile zipFile = new ZipFile(packagePath.toFile())) {
            ZipEntry entry = zipFile.getEntry(DATABASE_PATH);
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_DATABASE_BYTES) {
                throw invalidPackage("Backup database entry is invalid");
            }
            MessageDigest digest = sha256Digest();
            long written = 0;
            try (InputStream input = zipFile.getInputStream(entry);
                 OutputStream output = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (read == 0) {
                        continue;
                    }
                    written += read;
                    if (written > expected.sizeBytes() || written > MAX_DATABASE_BYTES) {
                        throw invalidPackage("Backup database exceeds its declared size");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String actualHash = HexFormat.of().formatHex(digest.digest());
            if (written != expected.sizeBytes() || !actualHash.equals(expected.sha256())) {
                throw invalidPackage("Backup database hash does not match manifest");
            }
        }
    }

    private void validateDiskSpace(Path workDirectory, long restoredSize) throws IOException {
        FileStore fileStore = Files.getFileStore(workDirectory);
        long currentSize = Files.size(this.fileStore.storage().databasePath());
        long margin = Math.max(MIN_FREE_SPACE_MARGIN, Math.addExact(restoredSize, currentSize) / 10);
        long required;
        try {
            required = Math.addExact(Math.addExact(restoredSize, currentSize), margin);
        } catch (ArithmeticException ex) {
            throw invalidPackage("Backup database size is invalid");
        }
        if (fileStore.getUsableSpace() < required) {
            throw new DataProtectionException(Reason.INSUFFICIENT_STORAGE, "Not enough disk space to restore backup");
        }
    }

    private void validateSupportedSchema(Path databasePath, int manifestSchemaVersion) {
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection();
             Statement statement = connection.createStatement()) {
            Set<String> tables;
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT name FROM sqlite_master
                    WHERE type = 'table' AND name NOT LIKE 'sqlite_%'
                    """)) {
                java.util.HashSet<String> names = new java.util.HashSet<>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                tables = Set.copyOf(names);
            }
            if (!ALLOWED_TABLES.containsAll(tables)
                    || !tables.containsAll(REQUIRED_BASE_TABLES)
                    || (manifestSchemaVersion >= 2 && !tables.contains("data_protection_events"))) {
                throw invalidPackage("Backup database schema contains unsupported objects");
            }
            try (ResultSet resultSet = statement.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type IN ('trigger', 'view')"
            )) {
                if (resultSet.next() && resultSet.getLong(1) > 0) {
                    throw invalidPackage("Backup database contains unsupported executable schema objects");
                }
            }
            try (ResultSet resultSet = statement.executeQuery("""
                    SELECT CAST(version AS INTEGER)
                    FROM flyway_schema_history
                    WHERE success = 1 AND version IS NOT NULL
                    ORDER BY installed_rank DESC LIMIT 1
                    """)) {
                if (!resultSet.next() || resultSet.getInt(1) != manifestSchemaVersion) {
                    throw invalidPackage("Backup schema version does not match manifest");
                }
            }
            assertZero(statement, """
                    SELECT COUNT(*) FROM documents d
                    WHERE d.knowledge_folder_id IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM knowledge_folders f WHERE f.id = d.knowledge_folder_id)
                    """);
            assertZero(statement, """
                    SELECT COUNT(*) FROM knowledge_graph_evidence e
                    WHERE NOT EXISTS (SELECT 1 FROM documents d WHERE d.id = e.document_id)
                       OR NOT EXISTS (SELECT 1 FROM chunks c WHERE c.id = e.chunk_id)
                    """);
        } catch (SQLException ex) {
            throw new DataProtectionException(Reason.INVALID_PACKAGE, "Backup database schema validation failed", ex);
        }
    }

    private Counts readCounts(Path databasePath) {
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection();
             Statement statement = connection.createStatement()) {
            return new Counts(
                    scalar(statement, "SELECT COUNT(*) FROM documents"),
                    scalar(statement, "SELECT COUNT(*) FROM chat_sessions"),
                    scalar(statement, "SELECT COUNT(*) FROM knowledge_graph_nodes")
            );
        } catch (SQLException ex) {
            throw new DataProtectionException(Reason.INVALID_PACKAGE, "Backup database summary could not be read", ex);
        }
    }

    private void writePackage(Path packagePath, Path snapshotPath, BackupManifest manifest) throws IOException {
        Path temporary = packagePath.resolveSibling(packagePath.getFileName() + ".tmp");
        try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(temporary))) {
            output.putNextEntry(new ZipEntry(MANIFEST_PATH));
            output.write(objectMapper.writeValueAsBytes(manifest));
            output.closeEntry();
            output.putNextEntry(new ZipEntry(DATABASE_PATH));
            Files.copy(snapshotPath, output);
            output.closeEntry();
        }
        try {
            Files.move(temporary, packagePath, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(temporary, packagePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void recordEvent(
            String operation,
            String status,
            String artifactName,
            String artifactSha256,
            int sourceVersion,
            int targetVersion,
            String message
    ) {
        long now = System.currentTimeMillis();
        try {
            jdbcTemplate.update("""
                            INSERT INTO data_protection_events (
                                id, operation, status, artifact_name, artifact_sha256,
                                source_schema_version, target_schema_version, message, started_at, completed_at
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    UUID.randomUUID().toString(), operation, status, artifactName, artifactSha256,
                    sourceVersion, targetVersion, message, now, now
            );
        } catch (RuntimeException ignored) {
            // 事件历史不能覆盖原始备份/恢复结果，也不能把可能含路径的异常写进日志。
        }
    }

    private static void assertZero(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            if (resultSet.next() && resultSet.getLong(1) > 0) {
                throw invalidPackage("Backup database contains broken business references");
            }
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getLong(1) : 0;
        }
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static boolean shouldPreferRestoreState(PendingRestoreState state, EventSummary event) {
        if (state == null) {
            return false;
        }
        return event == null || event.completedAt() == null || state.updatedAt() >= event.completedAt();
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static DataProtectionException invalidPackage(String message) {
        return new DataProtectionException(Reason.INVALID_PACKAGE, message);
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时快照残留不改变已生成备份的正确性，下次清理可再次处理。
        }
    }

    private record Counts(long documents, long chatSessions, long graphNodes) {
    }

    private record EventSummary(String operation, String status, Long completedAt) {
    }
}
