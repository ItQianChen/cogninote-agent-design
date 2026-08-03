package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/** 将可识别的历史结构转换为当前 Flyway 基线所需的最小形状。 */
@Component
public class LegacySchemaAdapter {
    private final SQLiteSnapshotService snapshotService;

    public LegacySchemaAdapter(SQLiteSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    public void adapt(Path databasePath, LegacySchemaInspector.Inspection inspection) {
        if (inspection.family() != SchemaFamily.LEGACY_MODEL_CONFIG) return;
        try (Connection connection = snapshotService.dataSource(databasePath, false).getConnection()) {
            if (!hasTable(connection, "model_configs")) {
                createModelConfigsTable(connection);
            }
            if (!hasColumn(connection, "model_config", "id") || !hasColumn(connection, "model_config", "api_key")
                    || !hasColumn(connection, "model_config", "provider")
                    || !hasColumn(connection, "model_config", "display_name")
                    || !hasColumn(connection, "model_config", "base_url")) {
                throw new DatabaseMigrationException("Unsupported legacy model_config shape");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT id, provider, display_name, base_url, api_key, chat_model, embedding_model, "
                            + "embedding_dimensions, temperature, top_k, created_at, updated_at FROM model_config");
                 ResultSet rs = statement.executeQuery();
                 PreparedStatement insert = connection.prepareStatement(
                         "INSERT INTO model_configs (id, role, provider, display_name, base_url, api_key, model_name, "
                                 + "embedding_dimensions, temperature, default_top_k, context_window_tokens, is_active, created_at, updated_at) "
                                 + "VALUES (?, 'CHAT', ?, ?, ?, ?, ?, ?, ?, ?, NULL, 1, ?, ?) "
                                 + "ON CONFLICT(id) DO UPDATE SET api_key=excluded.api_key, updated_at=excluded.updated_at")) {
                while (rs.next()) {
                    insert.setString(1, rs.getString("id"));
                    insert.setString(2, rs.getString("provider"));
                    insert.setString(3, rs.getString("display_name"));
                    insert.setString(4, rs.getString("base_url"));
                    insert.setString(5, rs.getString("api_key"));
                    insert.setString(6, rs.getString("chat_model"));
                    insert.setObject(7, rs.getObject("embedding_dimensions"));
                    insert.setObject(8, rs.getObject("temperature"));
                    insert.setObject(9, rs.getObject("top_k"));
                    insert.setLong(10, rs.getLong("created_at"));
                    insert.setLong(11, rs.getLong("updated_at"));
                    insert.executeUpdate();
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to adapt legacy model_config database", ex);
        }
    }

    private static boolean hasTable(Connection c, String name) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
            p.setString(1, name);
            try (ResultSet rs = p.executeQuery()) { return rs.next(); }
        }
    }

    private static boolean hasColumn(Connection c, String table, String column) throws SQLException {
        try (PreparedStatement p = c.prepareStatement("PRAGMA table_info(" + table + ")"); ResultSet rs = p.executeQuery()) {
            while (rs.next()) if (column.equals(rs.getString(2))) return true;
            return false;
        }
    }

    private static void createModelConfigsTable(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE model_configs (
                        id TEXT PRIMARY KEY,
                        role TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        base_url TEXT NOT NULL,
                        api_key TEXT,
                        model_name TEXT NOT NULL,
                        embedding_dimensions INTEGER,
                        embedding_requests_per_minute INTEGER,
                        embedding_tokens_per_minute INTEGER,
                        embedding_batch_size INTEGER,
                        temperature REAL,
                        default_top_k INTEGER,
                        context_window_tokens INTEGER,
                        is_active INTEGER NOT NULL DEFAULT 0,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """);
        }
    }
}
