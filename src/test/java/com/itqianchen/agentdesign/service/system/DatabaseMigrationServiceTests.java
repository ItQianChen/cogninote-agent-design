package com.itqianchen.agentdesign.service.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.properties.storage.StorageProperties;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMigrationServiceTests {

    @TempDir
    Path tempDir;

    @Test
    void emptyDatabaseMigratesToCurrentSchema() throws Exception {
        Fixture fixture = fixture(tempDir.resolve("fresh"));

        fixture.migrationService().migrateBeforeConnectionPool();

        assertThat(fixture.migrationService().currentSchemaVersion()).isEqualTo(2);
        assertThat(queryInt(fixture, "SELECT COUNT(*) FROM model_configs")).isEqualTo(3);
        assertThat(queryInt(fixture, "SELECT COUNT(*) FROM data_protection_events")).isZero();
    }

    @Test
    void unversionedCurrentBaselineIsAcceptedAndMigrated() throws Exception {
        Fixture fixture = fixture(tempDir.resolve("baseline"));
        fixture.storageInitializer().ensureInitialized();
        Flyway.configure()
                .dataSource(fixture.snapshotService().dataSource(fixture.databasePath(), false))
                .locations("classpath:db/migration")
                .target("1")
                .load()
                .migrate();
        try (Connection connection = fixture.snapshotService().dataSource(fixture.databasePath(), false).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE flyway_schema_history");
        }

        fixture.migrationService().migrateBeforeConnectionPool();

        assertThat(fixture.migrationService().currentSchemaVersion()).isEqualTo(2);
        assertThat(queryInt(fixture, "SELECT COUNT(*) FROM data_protection_events")).isZero();
        assertThat(fixture.storageInitializer().appStorage().internalBackupDir().toFile().listFiles())
                .isNotNull()
                .isNotEmpty();
    }

    @Test
    void unknownUnversionedDatabaseIsRejectedWithoutBaselining() throws Exception {
        Fixture fixture = fixture(tempDir.resolve("unknown"));
        fixture.storageInitializer().ensureInitialized();
        try (Connection connection = fixture.snapshotService().dataSource(fixture.databasePath(), false).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE legacy_notes (id TEXT PRIMARY KEY)");
        }

        assertThatThrownBy(fixture.migrationService()::migrateBeforeConnectionPool)
                .isInstanceOf(DatabaseMigrationException.class)
                .hasMessageContaining("original data was preserved");
        assertThat(queryInt(fixture, "SELECT COUNT(*) FROM sqlite_master WHERE name='flyway_schema_history'"))
                .isZero();
    }

    @Test
    void scheduledRestoreIsAppliedBeforeMigrationAndQueuesReindex() throws Exception {
        Fixture fixture = fixture(tempDir.resolve("restore-success"));
        fixture.migrationService().migrateBeforeConnectionPool();
        execute(fixture, "INSERT INTO app_settings VALUES ('test.value', 'before', 1)");
        String restoreId = java.util.UUID.randomUUID().toString();
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(),
                fixture.fileStore().restoredDatabase(restoreId)
        );
        try (Connection connection = fixture.snapshotService()
                .dataSource(fixture.fileStore().restoredDatabase(restoreId), false)
                .getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE app_settings SET setting_value='after' WHERE setting_key='test.value'");
        }
        PendingRestoreState state = restoreState(restoreId);
        fixture.fileStore().writeState(state);
        fixture.fileStore().schedule(state);

        DatabaseMigrationService restarted = new DatabaseMigrationService(
                fixture.storageInitializer(), fixture.snapshotService(), fixture.pendingRestoreService()
        );
        restarted.migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("after");
        assertThat(fixture.fileStore().readState(restoreId).phase()).isEqualTo(RestorePhase.REINDEXING);
        assertThat(fixture.fileStore().pendingReindex()).isPresent();
    }

    @Test
    void invalidScheduledRestoreKeepsOriginalDatabaseAndRecordsRollback() throws Exception {
        Fixture fixture = fixture(tempDir.resolve("restore-failure"));
        fixture.migrationService().migrateBeforeConnectionPool();
        execute(fixture, "INSERT INTO app_settings VALUES ('test.value', 'before', 1)");
        String restoreId = java.util.UUID.randomUUID().toString();
        Files.createDirectories(fixture.fileStore().restoreWorkDir(restoreId));
        Files.write(fixture.fileStore().restoredDatabase(restoreId), new byte[]{1, 2, 3});
        PendingRestoreState state = restoreState(restoreId);
        fixture.fileStore().writeState(state);
        fixture.fileStore().schedule(state);

        DatabaseMigrationService restarted = new DatabaseMigrationService(
                fixture.storageInitializer(), fixture.snapshotService(), fixture.pendingRestoreService()
        );
        restarted.migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("before");
        assertThat(fixture.fileStore().readState(restoreId).phase()).isEqualTo(RestorePhase.ROLLED_BACK);
        assertThat(fixture.fileStore().pendingRestore()).isEmpty();
    }

    @Test
    void swappingPhaseBeforeReplacementResumesRestore() throws Exception {
        Fixture fixture = initializedRestoreFixture(tempDir.resolve("restore-swapping-before"));
        String restoreId = java.util.UUID.randomUUID().toString();
        PendingRestoreState scheduled = stageRestore(fixture, restoreId);
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(), fixture.fileStore().restoreRollbackDatabase(restoreId)
        );
        fixture.fileStore().schedule(scheduled);
        fixture.fileStore().writePendingState(scheduled.withPhase(RestorePhase.SWAPPING, "swapping", 2L));

        restartMigration(fixture).migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("after");
        assertThat(fixture.fileStore().pendingRestore()).isEmpty();
        assertThat(fixture.fileStore().pendingReindex()).isPresent();
    }

    @Test
    void swappingPhaseAfterReplacementCanBeReplayed() throws Exception {
        Fixture fixture = initializedRestoreFixture(tempDir.resolve("restore-swapping-after"));
        String restoreId = java.util.UUID.randomUUID().toString();
        PendingRestoreState scheduled = stageRestore(fixture, restoreId);
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(), fixture.fileStore().restoreRollbackDatabase(restoreId)
        );
        fixture.fileStore().schedule(scheduled);
        fixture.fileStore().writePendingState(scheduled.withPhase(RestorePhase.SWAPPING, "swapping", 2L));
        fixture.snapshotService().replaceDatabase(
                fixture.fileStore().restoredDatabase(restoreId), fixture.databasePath()
        );

        restartMigration(fixture).migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("after");
        assertThat(fixture.fileStore().pendingReindex()).isPresent();
    }

    @Test
    void validatingPhaseResumesWithoutReplacingRollbackSnapshot() throws Exception {
        Fixture fixture = initializedRestoreFixture(tempDir.resolve("restore-validating"));
        String restoreId = java.util.UUID.randomUUID().toString();
        PendingRestoreState scheduled = stageRestore(fixture, restoreId);
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(), fixture.fileStore().restoreRollbackDatabase(restoreId)
        );
        fixture.fileStore().schedule(scheduled);
        fixture.snapshotService().replaceDatabase(
                fixture.fileStore().restoredDatabase(restoreId), fixture.databasePath()
        );
        fixture.fileStore().writePendingState(scheduled.withPhase(RestorePhase.VALIDATING, "validating", 3L));

        restartMigration(fixture).migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("after");
        assertThat(fixture.fileStore().pendingReindex()).isPresent();
    }

    @Test
    void invalidLiveDatabaseDuringValidatingRollsBackOriginal() throws Exception {
        Fixture fixture = initializedRestoreFixture(tempDir.resolve("restore-validating-rollback"));
        String restoreId = java.util.UUID.randomUUID().toString();
        PendingRestoreState scheduled = stageRestore(fixture, restoreId);
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(), fixture.fileStore().restoreRollbackDatabase(restoreId)
        );
        fixture.fileStore().schedule(scheduled);
        fixture.fileStore().writePendingState(scheduled.withPhase(RestorePhase.VALIDATING, "validating", 3L));
        Files.write(fixture.databasePath(), new byte[]{1, 2, 3});

        restartMigration(fixture).migrateBeforeConnectionPool();

        assertThat(queryString(fixture, "SELECT setting_value FROM app_settings WHERE setting_key='test.value'"))
                .isEqualTo("before");
        assertThat(fixture.fileStore().readState(restoreId).phase()).isEqualTo(RestorePhase.ROLLED_BACK);
        assertThat(fixture.fileStore().pendingRestore()).isEmpty();
    }

    @Test
    void reindexingPhaseCompletesStartupHandoffIdempotently() throws Exception {
        Fixture fixture = initializedRestoreFixture(tempDir.resolve("restore-reindexing"));
        String restoreId = java.util.UUID.randomUUID().toString();
        PendingRestoreState scheduled = restoreState(restoreId);
        fixture.fileStore().schedule(scheduled);
        fixture.fileStore().writePendingState(scheduled.withPhase(RestorePhase.REINDEXING, "reindexing", 4L));
        Files.writeString(fixture.fileStore().storage().luceneIndexDir().resolve("stale-index"), "stale");

        restartMigration(fixture).migrateBeforeConnectionPool();

        assertThat(fixture.fileStore().pendingRestore()).isEmpty();
        assertThat(fixture.fileStore().pendingReindex()).isPresent();
        assertThat(fixture.fileStore().storage().luceneIndexDir().resolve("stale-index")).doesNotExist();
        assertThat(fixture.fileStore().restoreWorkDir(restoreId).resolve("previous-lucene/stale-index"))
                .exists();
    }

    private static Fixture fixture(Path storageRoot) {
        AppStorageInitializer storageInitializer = new AppStorageInitializer(
                new StorageProperties(storageRoot.toString(), null)
        );
        SQLiteSnapshotService snapshotService = new SQLiteSnapshotService();
        DataProtectionFileStore fileStore = new DataProtectionFileStore(storageInitializer, new ObjectMapper());
        PendingRestoreService pendingRestoreService = new PendingRestoreService(fileStore, snapshotService);
        DatabaseMigrationService migrationService = new DatabaseMigrationService(
                storageInitializer,
                snapshotService,
                pendingRestoreService
        );
        return new Fixture(
                storageInitializer,
                snapshotService,
                fileStore,
                pendingRestoreService,
                migrationService,
                storageInitializer.appStorage().databasePath()
        );
    }

    private static Fixture initializedRestoreFixture(Path storageRoot) throws Exception {
        Fixture fixture = fixture(storageRoot);
        fixture.migrationService().migrateBeforeConnectionPool();
        execute(fixture, "INSERT INTO app_settings VALUES ('test.value', 'before', 1)");
        return fixture;
    }

    private static PendingRestoreState stageRestore(Fixture fixture, String restoreId) throws Exception {
        fixture.snapshotService().createSnapshot(
                fixture.databasePath(), fixture.fileStore().restoredDatabase(restoreId)
        );
        try (Connection connection = fixture.snapshotService()
                .dataSource(fixture.fileStore().restoredDatabase(restoreId), false)
                .getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("UPDATE app_settings SET setting_value='after' WHERE setting_key='test.value'");
        }
        return restoreState(restoreId);
    }

    private static DatabaseMigrationService restartMigration(Fixture fixture) {
        return new DatabaseMigrationService(
                fixture.storageInitializer(), fixture.snapshotService(), fixture.pendingRestoreService()
        );
    }

    private static void execute(Fixture fixture, String sql) throws Exception {
        try (Connection connection = fixture.snapshotService().dataSource(fixture.databasePath(), false).getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryString(Fixture fixture, String sql) throws Exception {
        try (Connection connection = fixture.snapshotService().dataSource(fixture.databasePath(), true).getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static PendingRestoreState restoreState(String restoreId) {
        long now = System.currentTimeMillis();
        return new PendingRestoreState(
                restoreId, RestorePhase.SCHEDULED, "scheduled", "0.1.70", 2,
                now, now, true, 0, 0, 0
        );
    }

    private static int queryInt(Fixture fixture, String sql) throws Exception {
        try (Connection connection = fixture.snapshotService().dataSource(fixture.databasePath(), true).getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private record Fixture(
            AppStorageInitializer storageInitializer,
            SQLiteSnapshotService snapshotService,
            DataProtectionFileStore fileStore,
            PendingRestoreService pendingRestoreService,
            DatabaseMigrationService migrationService,
            Path databasePath
    ) {
    }
}
