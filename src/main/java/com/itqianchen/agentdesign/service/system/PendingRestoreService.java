package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import com.itqianchen.agentdesign.domain.vo.storage.AppStorage;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** 在任何数据库连接池打开前应用或回滚已安排的恢复。 */
@Component
public class PendingRestoreService {

    private final DataProtectionFileStore fileStore;
    private final SQLiteSnapshotService snapshotService;

    public PendingRestoreService(DataProtectionFileStore fileStore, SQLiteSnapshotService snapshotService) {
        this.fileStore = fileStore;
        this.snapshotService = snapshotService;
    }

    /**
     * 把已预检数据库放到 live 路径，并保留当前数据库快照供后续迁移失败时回滚。
     *
     * <p>pending marker 是跨进程权威状态。每个阶段都允许重复执行，保证进程在任意持久化点退出后
     * 能继续恢复或确定性回滚。</p>
     *
     * @return 没有 pending restore，或恢复已回滚/进入重建阶段时为空
     */
    public Optional<AppliedRestore> applyBeforeMigration() {
        Optional<PendingRestoreState> pending = fileStore.pendingRestore();
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        PendingRestoreState state = pending.get();
        try {
            return switch (state.phase()) {
                case SCHEDULED -> beginScheduledRestore(state);
                case SWAPPING -> resumeSwapping(state);
                case VALIDATING -> resumeValidating(state);
                case REINDEXING -> {
                    resumeReindexing(state);
                    yield Optional.empty();
                }
                case ROLLED_BACK, COMPLETED, REINDEX_FAILED, DISCARDED -> {
                    fileStore.clearPendingRestore();
                    yield Optional.empty();
                }
                case PREFLIGHTED -> throw new DatabaseMigrationException(
                        "Pending restore marker has an invalid phase"
                );
            };
        } catch (RuntimeException ex) {
            if (state.phase() == RestorePhase.SCHEDULED
                    || state.phase() == RestorePhase.SWAPPING
                    || state.phase() == RestorePhase.VALIDATING) {
                return recoverInterruptedRestore(state, ex);
            }
            throw ex;
        }
    }

    /** 恢复数据库迁移成功后废弃旧索引，并安排从 SQLite 全量重建。 */
    public void complete(AppliedRestore appliedRestore) {
        PendingRestoreState reindexing = appliedRestore.state().withPhase(
                RestorePhase.REINDEXING,
                "数据库恢复完成，正在重建搜索索引。",
                System.currentTimeMillis()
        );
        // 先持久化阶段；后续任何一步中断都由 REINDEXING 的幂等续跑补齐。
        fileStore.writePendingState(reindexing);
        resumeReindexing(reindexing);
    }

    /** 恢复库无法迁移或校验时还原恢复前数据库。 */
    public void rollback(AppliedRestore appliedRestore) {
        restoreRollbackDatabase(appliedRestore.state(), appliedRestore.rollbackDatabase());
    }

    private Optional<AppliedRestore> beginScheduledRestore(PendingRestoreState state) {
        Path stagedDatabase = requireValidStagedDatabase(state.restoreId());
        Path rollbackDatabase = fileStore.restoreRollbackDatabase(state.restoreId());
        AppStorage storage = fileStore.storage();
        boolean hadLiveDatabase = isNonEmptyFile(rollbackDatabase);
        if (hadLiveDatabase) {
            // marker 可能仍是 SCHEDULED，但已有 rollback 表示前一次启动已越过快照点，禁止覆盖旧数据。
            snapshotService.validate(rollbackDatabase);
        } else {
            hadLiveDatabase = isNonEmptyFile(storage.databasePath());
            if (hadLiveDatabase) {
                snapshotService.createSnapshot(storage.databasePath(), rollbackDatabase);
                snapshotService.validate(rollbackDatabase);
            }
        }

        PendingRestoreState swapping = state.withPhase(
                RestorePhase.SWAPPING,
                "正在替换数据库。",
                System.currentTimeMillis()
        );
        fileStore.writePendingState(swapping);
        return resumeSwapping(swapping, stagedDatabase, rollbackDatabase, hadLiveDatabase);
    }

    private Optional<AppliedRestore> resumeSwapping(PendingRestoreState state) {
        Path stagedDatabase = requireValidStagedDatabase(state.restoreId());
        Path rollbackDatabase = fileStore.restoreRollbackDatabase(state.restoreId());
        boolean hadLiveDatabase = isNonEmptyFile(rollbackDatabase);
        if (hadLiveDatabase) {
            snapshotService.validate(rollbackDatabase);
        }
        return resumeSwapping(state, stagedDatabase, rollbackDatabase, hadLiveDatabase);
    }

    private Optional<AppliedRestore> resumeSwapping(
            PendingRestoreState state,
            Path stagedDatabase,
            Path rollbackDatabase,
            boolean hadLiveDatabase
    ) {
        snapshotService.replaceDatabase(stagedDatabase, fileStore.storage().databasePath());
        PendingRestoreState validating = state.withPhase(
                RestorePhase.VALIDATING,
                "正在迁移并检查恢复数据库。",
                System.currentTimeMillis()
        );
        fileStore.writePendingState(validating);
        return resumeValidating(validating, rollbackDatabase, hadLiveDatabase);
    }

    private Optional<AppliedRestore> resumeValidating(PendingRestoreState state) {
        Path rollbackDatabase = fileStore.restoreRollbackDatabase(state.restoreId());
        return resumeValidating(state, rollbackDatabase, isNonEmptyFile(rollbackDatabase));
    }

    private Optional<AppliedRestore> resumeValidating(
            PendingRestoreState state,
            Path rollbackDatabase,
            boolean hadLiveDatabase
    ) {
        Path liveDatabase = fileStore.storage().databasePath();
        if (!isNonEmptyFile(liveDatabase)) {
            throw new DatabaseMigrationException("Restored live database is missing");
        }
        snapshotService.validate(liveDatabase);
        return Optional.of(new AppliedRestore(state, rollbackDatabase, hadLiveDatabase));
    }

    private Optional<AppliedRestore> recoverInterruptedRestore(PendingRestoreState state, RuntimeException cause) {
        Path rollbackDatabase = fileStore.restoreRollbackDatabase(state.restoreId());
        if (isNonEmptyFile(rollbackDatabase)) {
            restoreRollbackDatabase(state, rollbackDatabase);
            return Optional.empty();
        }
        if (state.phase() == RestorePhase.SCHEDULED) {
            markRolledBack(state, "恢复包无法应用，已继续使用原数据。");
            return Optional.empty();
        }
        throw new DatabaseMigrationException(
                "Interrupted restore cannot continue and no rollback database is available",
                cause
        );
    }

    private void restoreRollbackDatabase(PendingRestoreState state, Path rollbackDatabase) {
        if (!isNonEmptyFile(rollbackDatabase)) {
            throw new DatabaseMigrationException("Restore failed and no rollback database is available");
        }
        snapshotService.replaceDatabase(rollbackDatabase, fileStore.storage().databasePath());
        snapshotService.validate(fileStore.storage().databasePath());
        restorePreviousLucene(state.restoreId());
        fileStore.clearPendingReindex();
        markRolledBack(state, "恢复失败，已自动还原恢复前数据。");
    }

    private void markRolledBack(PendingRestoreState state, String message) {
        PendingRestoreState rolledBack = state.withPhase(
                RestorePhase.ROLLED_BACK,
                message,
                System.currentTimeMillis()
        );
        fileStore.writePendingState(rolledBack);
        fileStore.cleanupRestoreArtifacts(rolledBack.restoreId());
        fileStore.clearPendingRestore();
    }

    private Path requireValidStagedDatabase(String restoreId) {
        Path stagedDatabase = fileStore.restoredDatabase(restoreId);
        if (!isNonEmptyFile(stagedDatabase)) {
            throw new DatabaseMigrationException("Pending restore database is missing");
        }
        snapshotService.validate(stagedDatabase);
        return stagedDatabase;
    }

    private void resumeReindexing(PendingRestoreState state) {
        snapshotService.validate(fileStore.storage().databasePath());
        invalidateLucene(state.restoreId());
        fileStore.scheduleReindex(state);
        fileStore.cleanupRestoredDatabase(state.restoreId());
        pruneRestoreSnapshots(fileStore.storage().internalBackupDir());
        fileStore.clearPendingRestore();
    }

    private void restorePreviousLucene(String restoreId) {
        Path indexDirectory = fileStore.storage().luceneIndexDir();
        Path staleDirectory = fileStore.restoreWorkDir(restoreId).resolve("previous-lucene");
        if (!Files.exists(staleDirectory)) {
            return;
        }
        try {
            deleteRecursively(indexDirectory);
            Files.move(staleDirectory, indexDirectory, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to restore Lucene after database rollback", ex);
        }
    }

    private void invalidateLucene(String restoreId) {
        Path indexDirectory = fileStore.storage().luceneIndexDir();
        Path staleDirectory = fileStore.restoreWorkDir(restoreId).resolve("previous-lucene");
        try {
            Files.createDirectories(staleDirectory.getParent());
            if (!Files.exists(staleDirectory) && Files.exists(indexDirectory)) {
                Files.move(indexDirectory, staleDirectory, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.createDirectories(indexDirectory);
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to invalidate Lucene after restore", ex);
        }
    }

    private static boolean isNonEmptyFile(Path path) {
        try {
            return Files.isRegularFile(path) && Files.size(path) > 0;
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to inspect database file", ex);
        }
    }

    private static void pruneRestoreSnapshots(Path directory) {
        try (var files = Files.list(directory)) {
            java.util.List<Path> snapshots = files
                    .filter(path -> path.getFileName().toString().startsWith("pre-restore-"))
                    .sorted(Comparator.comparingLong(PendingRestoreService::lastModified).reversed())
                    .toList();
            for (int index = 3; index < snapshots.size(); index++) {
                Files.deleteIfExists(snapshots.get(index));
            }
        } catch (IOException ignored) {
            // 快照保留清理失败不影响已验证的恢复结果。
        }
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

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return Long.MIN_VALUE;
        }
    }

    public record AppliedRestore(
            PendingRestoreState state,
            Path rollbackDatabase,
            boolean hadLiveDatabase
    ) {
    }
}
