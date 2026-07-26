package com.itqianchen.agentdesign.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException.Reason;
import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 受控备份导出、恢复 inbox 和跨进程 marker 的路径所有者。 */
@Component
public class DataProtectionFileStore {

    private static final String PENDING_RESTORE_FILE = "pending-restore.json";
    private static final String PENDING_REINDEX_FILE = "pending-reindex.json";
    private static final String STATE_FILE = "restore-state.json";

    private final AppStorageInitializer storageInitializer;
    private final ObjectMapper objectMapper;

    public DataProtectionFileStore(AppStorageInitializer storageInitializer, ObjectMapper objectMapper) {
        this.storageInitializer = storageInitializer;
        this.objectMapper = objectMapper;
    }

    public AppStorage storage() {
        storageInitializer.ensureInitialized();
        return storageInitializer.appStorage();
    }

    public Path exportPath(String backupId) {
        return storage().backupExportDir().resolve(normalizeId(backupId) + ".cogninote-backup");
    }

    public Path inboxPath(String importId) {
        return storage().restoreInboxDir().resolve(normalizeId(importId) + ".cogninote-backup");
    }

    public Path restoreWorkDir(String restoreId) {
        return storage().restoreWorkDir().resolve(normalizeId(restoreId));
    }

    public Path restoredDatabase(String restoreId) {
        return restoreWorkDir(restoreId).resolve("cogninote.db");
    }

    public Path restoreRollbackDatabase(String restoreId) {
        return storage().internalBackupDir().resolve("pre-restore-" + normalizeId(restoreId) + ".db");
    }

    public void writeState(PendingRestoreState state) {
        writeJson(stateFile(state.restoreId()), state);
    }

    public PendingRestoreState readState(String restoreId) {
        return readJson(stateFile(restoreId));
    }

    public Optional<PendingRestoreState> latestState() {
        try (var directories = Files.list(storage().restoreWorkDir())) {
            return directories
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparingLong(DataProtectionFileStore::lastModified).reversed())
                    .map(path -> path.resolve(STATE_FILE))
                    .filter(Files::isRegularFile)
                    .map(this::readJson)
                    .findFirst();
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to read restore status", ex);
        }
    }

    /** 删除异常退出、取消和终态任务遗留的敏感副本。 */
    public void cleanupStaleTransientFiles() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(24));
        cleanupDirectory(storage().backupExportDir(), cutoff);
        cleanupDirectory(storage().restoreInboxDir(), cutoff);
        cleanupRestoreWork(cutoff);
    }

    public void schedule(PendingRestoreState state) {
        if (Files.exists(pendingRestorePath())) {
            throw new DataProtectionException(Reason.CONFLICT, "Another restore is already scheduled");
        }
        writePendingState(state);
    }

    /** 同步恢复状态和启动 marker；旧阶段残留也必须能被下次启动安全重放。 */
    public void writePendingState(PendingRestoreState state) {
        writeState(state);
        writeJson(pendingRestorePath(), state);
    }

    public Optional<PendingRestoreState> pendingRestore() {
        Path path = pendingRestorePath();
        return Files.isRegularFile(path) ? Optional.of(readJson(path)) : Optional.empty();
    }

    public void clearPendingRestore() {
        deleteMarker(pendingRestorePath());
    }

    public void scheduleReindex(PendingRestoreState state) {
        writeJson(pendingReindexPath(), state);
    }

    public Optional<PendingRestoreState> pendingReindex() {
        Path path = pendingReindexPath();
        return Files.isRegularFile(path) ? Optional.of(readJson(path)) : Optional.empty();
    }

    public void clearPendingReindex() {
        deleteMarker(pendingReindexPath());
    }

    /** 只保留状态元数据，删除工作目录中的数据库、索引和临时文件。 */
    public void cleanupRestoreArtifacts(String restoreId) {
        Path workDirectory = restoreWorkDir(restoreId);
        if (!Files.isDirectory(workDirectory)) {
            return;
        }
        try (var children = Files.list(workDirectory)) {
            for (Path child : children
                    .filter(path -> !STATE_FILE.equals(path.getFileName().toString()))
                    .toList()) {
                deleteRecursively(child);
            }
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to clean restore work files", ex);
        }
    }

    /** 恢复数据库已换入 live 后只删除 staged SQLite，旧索引保留到重建完成。 */
    public void cleanupRestoredDatabase(String restoreId) {
        Path database = restoredDatabase(restoreId);
        try {
            Files.deleteIfExists(database);
            Files.deleteIfExists(Path.of(database + "-wal"));
            Files.deleteIfExists(Path.of(database + "-shm"));
            Files.deleteIfExists(Path.of(database + "-journal"));
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to clean restored database copy", ex);
        }
    }

    private Path stateFile(String restoreId) {
        return restoreWorkDir(restoreId).resolve(STATE_FILE);
    }

    private Path pendingRestorePath() {
        return storage().dataProtectionDir().resolve(PENDING_RESTORE_FILE);
    }

    private Path pendingReindexPath() {
        return storage().dataProtectionDir().resolve(PENDING_REINDEX_FILE);
    }

    private void writeJson(Path path, PendingRestoreState state) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            objectMapper.writeValue(temporary.toFile(), state);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to persist restore state", ex);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 原始写入异常比临时文件清理失败更有诊断价值。
            }
        }
    }

    private PendingRestoreState readJson(Path path) {
        try {
            return objectMapper.readValue(path.toFile(), PendingRestoreState.class);
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.INVALID_PACKAGE, "Restore state is invalid", ex);
        }
    }

    private static String normalizeId(String id) {
        try {
            return UUID.fromString(id).toString();
        } catch (RuntimeException ex) {
            throw new DataProtectionException(Reason.NOT_FOUND, "Data protection item was not found");
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteMarker(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to clear restore marker", ex);
        }
    }

    private static void cleanupDirectory(Path directory, Instant cutoff) {
        try (var files = Files.list(directory)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                }
            }
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to clean stale data protection files", ex);
        }
    }

    private void cleanupRestoreWork(Instant cutoff) {
        String pendingRestoreId = pendingRestore().map(PendingRestoreState::restoreId).orElse(null);
        String pendingReindexId = pendingReindex().map(PendingRestoreState::restoreId).orElse(null);
        try (var directories = Files.list(storage().restoreWorkDir())) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path statePath = directory.resolve(STATE_FILE);
                if (!Files.isRegularFile(statePath)) {
                    if (Files.getLastModifiedTime(directory).toInstant().isBefore(cutoff)) {
                        deleteRecursively(directory);
                    }
                    continue;
                }
                PendingRestoreState state = readJson(statePath);
                if (state.restoreId().equals(pendingRestoreId) || state.restoreId().equals(pendingReindexId)) {
                    continue;
                }
                if (state.phase() == RestorePhase.PREFLIGHTED
                        && Instant.ofEpochMilli(state.updatedAt()).isBefore(cutoff)) {
                    PendingRestoreState discarded = state.withPhase(
                            RestorePhase.DISCARDED,
                            "恢复预检已过期，敏感工作副本已清理。",
                            System.currentTimeMillis()
                    );
                    writeState(discarded);
                    cleanupRestoreArtifacts(discarded.restoreId());
                } else if (isTerminal(state)) {
                    cleanupRestoreArtifacts(state.restoreId());
                }
            }
        } catch (IOException ex) {
            throw new DataProtectionException(Reason.IO_FAILURE, "Failed to clean stale restore work", ex);
        }
    }

    private static boolean isTerminal(PendingRestoreState state) {
        return switch (state.phase()) {
            case DISCARDED, COMPLETED, ROLLED_BACK, REINDEX_FAILED -> true;
            default -> false;
        };
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
