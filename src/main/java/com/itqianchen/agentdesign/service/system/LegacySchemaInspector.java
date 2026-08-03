package com.itqianchen.agentdesign.service.system;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/** 只读取 SQLite 元数据，不以应用版本号判断旧库是否可迁移。 */
@Component
public class LegacySchemaInspector {
    private final SQLiteSnapshotService snapshotService;

    public LegacySchemaInspector(SQLiteSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public Inspection inspect(Path databasePath) {
        if (!java.nio.file.Files.isRegularFile(databasePath)) {
            return new Inspection(SchemaFamily.EMPTY, 0, Set.of());
        }
        try (Connection connection = snapshotService.dataSource(databasePath, true).getConnection()) {
            Set<String> tables = names(connection, "SELECT name FROM sqlite_master WHERE type='table'");
            if (tables.contains("flyway_schema_history")) {
                return new Inspection(SchemaFamily.FLYWAY, latestFlywayVersion(connection), tables);
            }
            if (tables.contains("model_configs") && tables.contains("knowledge_folders")) {
                return new Inspection(SchemaFamily.LEGACY_CURRENT_TABLES, 0, tables);
            }
            if (tables.contains("model_config")) {
                return new Inspection(SchemaFamily.LEGACY_MODEL_CONFIG, 0, tables);
            }
            return new Inspection(tables.isEmpty() ? SchemaFamily.EMPTY : SchemaFamily.UNKNOWN, 0, tables);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to inspect database schema", ex);
        }
    }

    private static Set<String> names(Connection connection, String sql) throws SQLException {
        Set<String> result = new HashSet<>();
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) result.add(rs.getString(1));
        }
        return Set.copyOf(result);
    }

    private static int latestFlywayVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
                "SELECT version FROM flyway_schema_history WHERE success=1 AND version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")) {
            return rs.next() ? Integer.parseInt(rs.getString(1)) : 0;
        }
    }

    public record Inspection(SchemaFamily family, int version, Set<String> tables) {}
}
