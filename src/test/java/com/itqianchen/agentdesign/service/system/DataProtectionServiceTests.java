package com.itqianchen.agentdesign.service.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.dto.system.BackupCreateResponse;
import com.itqianchen.agentdesign.domain.dto.system.RestoreStatusResponse;
import com.itqianchen.agentdesign.domain.enums.system.RestorePhase;
import com.itqianchen.agentdesign.domain.exception.storage.DataProtectionException;
import com.itqianchen.agentdesign.domain.vo.storage.PendingRestoreState;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataProtectionServiceTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    DataProtectionService dataProtectionService;

    @Autowired
    DataProtectionFileStore fileStore;

    @Autowired
    SQLiteSnapshotService snapshotService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @Order(1)
    void emptyHistoryReturnsStatusWithoutUnboxingNullTimestamp() {
        assertThat(dataProtectionService.status().schemaVersion()).isEqualTo(3);
        assertThat(dataProtectionService.status().lastOperation()).isNull();
        assertThat(dataProtectionService.status().lastCompletedAt()).isNull();
    }

    @Test
    @Order(2)
    void fullBackupRetainsApiKeyAndCanBeScheduledAfterPreflight() throws Exception {
        jdbcTemplate.update("UPDATE model_configs SET api_key = ? WHERE id = 'active-chat'", "fixture-api-key");

        BackupCreateResponse backup = dataProtectionService.createBackup();
        assertThat(backup.containsSecrets()).isTrue();
        assertThat(backup.schemaVersion()).isEqualTo(3);

        String importId = UUID.randomUUID().toString();
        Files.copy(fileStore.exportPath(backup.backupId()), fileStore.inboxPath(importId));
        RestoreStatusResponse restore = dataProtectionService.preflight(importId);

        assertThat(restore.phase()).isEqualTo(RestorePhase.PREFLIGHTED);
        assertThat(restore.containsSecrets()).isTrue();
        try (Connection connection = snapshotService
                .dataSource(fileStore.restoredDatabase(restore.restoreId()), true)
                .getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT api_key FROM model_configs WHERE id = 'active-chat'"
             )) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("fixture-api-key");
        }

        RestoreStatusResponse discarded = dataProtectionService.discardRestore(restore.restoreId());
        assertThat(discarded.phase()).isEqualTo(RestorePhase.DISCARDED);
        assertThat(fileStore.restoredDatabase(restore.restoreId())).doesNotExist();
        assertThat(dataProtectionService.discardRestore(restore.restoreId()).phase())
                .isEqualTo(RestorePhase.DISCARDED);

        String staleImportId = UUID.randomUUID().toString();
        Files.copy(fileStore.exportPath(backup.backupId()), fileStore.inboxPath(staleImportId));
        RestoreStatusResponse staleRestore = dataProtectionService.preflight(staleImportId);
        PendingRestoreState staleState = fileStore.readState(staleRestore.restoreId()).withPhase(
                RestorePhase.PREFLIGHTED,
                "stale",
                System.currentTimeMillis() - Duration.ofHours(25).toMillis()
        );
        fileStore.writeState(staleState);
        dataProtectionService.status();
        assertThat(fileStore.readState(staleRestore.restoreId()).phase()).isEqualTo(RestorePhase.DISCARDED);
        assertThat(fileStore.restoredDatabase(staleRestore.restoreId())).doesNotExist();

        String scheduledImportId = UUID.randomUUID().toString();
        Files.copy(fileStore.exportPath(backup.backupId()), fileStore.inboxPath(scheduledImportId));
        RestoreStatusResponse scheduledRestore = dataProtectionService.preflight(scheduledImportId);
        dataProtectionService.scheduleRestore(scheduledRestore.restoreId());
        assertThat(fileStore.pendingRestore()).isPresent();
        assertThat(dataProtectionService.restoreStatus(scheduledRestore.restoreId()).phase())
                .isEqualTo(RestorePhase.SCHEDULED);
    }

    @Test
    @Order(3)
    void preflightRejectsArchiveWithUnexpectedTraversalEntry() throws Exception {
        String importId = UUID.randomUUID().toString();
        Path packagePath = fileStore.inboxPath(importId);
        try (OutputStream fileOutput = Files.newOutputStream(packagePath);
             ZipOutputStream zipOutput = new ZipOutputStream(fileOutput)) {
            zipOutput.putNextEntry(new ZipEntry("../cogninote.db"));
            zipOutput.write(new byte[]{1, 2, 3});
            zipOutput.closeEntry();
        }

        assertThatThrownBy(() -> dataProtectionService.preflight(importId))
                .isInstanceOf(DataProtectionException.class)
                .hasMessageContaining("unexpected entries");
    }
}
