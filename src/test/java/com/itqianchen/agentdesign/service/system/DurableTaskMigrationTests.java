package com.itqianchen.agentdesign.service.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteDataSource;

class DurableTaskMigrationTests {

    @TempDir
    Path tempDir;

    @Test
    void v2HistoryMigratesAndActiveRunsBecomeInterrupted() throws Exception {
        SQLiteDataSource dataSource = dataSource(tempDir.resolve("migration.db"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("2").load().migrate();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(insertLegacyRun("completed-run", "COMPLETED", 100L, 200L));
            statement.execute(insertLegacyRun("running-run", "RUNNING", 300L, null));
        }

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();

        assertThat(queryString(dataSource, "SELECT status FROM durable_task_runs WHERE id='completed-run'"))
                .isEqualTo("COMPLETED");
        assertThat(queryString(dataSource, "SELECT status FROM durable_task_runs WHERE id='running-run'"))
                .isEqualTo("INTERRUPTED");
        assertThat(queryString(dataSource, "SELECT error_code FROM durable_task_runs WHERE id='running-run'"))
                .isEqualTo("LEGACY_PAYLOAD_UNAVAILABLE");
        assertThat(queryInt(dataSource, "SELECT payload_version FROM durable_task_runs WHERE id='running-run'"))
                .isZero();
        assertThat(queryInt(dataSource, "SELECT resumable FROM durable_task_runs WHERE id='running-run'"))
                .isZero();
        assertThat(columns(dataSource, "knowledge_folder_runs"))
                .contains("scope_type", "scanned_count", "error_detail")
                .doesNotContain("status", "operation", "payload_json");
    }

    private static SQLiteDataSource dataSource(Path database) throws Exception {
        Files.deleteIfExists(database);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        return dataSource;
    }

    private static String insertLegacyRun(String id, String status, long startedAt, Long completedAt) {
        String completed = completedAt == null ? "NULL" : completedAt.toString();
        return """
                INSERT INTO knowledge_folder_runs (
                    id, scope_type, scope_id, operation, status, phase,
                    progress_current, progress_total, queued_at, started_at, completed_at,
                    created_at, updated_at
                ) VALUES (
                    '%s', 'KNOWLEDGE_FOLDER', 'folder-1', 'SYNC', '%s', '%s',
                    1, 2, 50, %d, %s, 50, 60
                )
                """.formatted(id, status, status, startedAt, completed);
    }

    private static Set<String> columns(SQLiteDataSource dataSource, String table) throws Exception {
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (resultSet.next()) {
                columns.add(resultSet.getString("name"));
            }
        }
        return columns;
    }

    private static String queryString(SQLiteDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static int queryInt(SQLiteDataSource dataSource, String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }
}
