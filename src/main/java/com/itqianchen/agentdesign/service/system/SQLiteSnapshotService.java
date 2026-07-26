package com.itqianchen.agentdesign.service.system;

import com.itqianchen.agentdesign.domain.exception.storage.DatabaseMigrationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import org.sqlite.SQLiteDataSource;
import org.springframework.stereotype.Component;

/**
 * SQLite 一致性快照和离线文件替换边界。
 *
 * <p>在线快照使用 SQLite backup API，避免直接复制 WAL 模式下可能尚未合并的数据库文件。</p>
 */
@Component
public class SQLiteSnapshotService {

    private static final int SQLITE_OK = 0;

    /**
     * 为当前 SQLite 数据库生成事务一致快照。
     *
     * @param sourceDatabase 当前数据库路径
     * @param targetDatabase 快照目标路径，必须不存在或可被覆盖
     */
    public void createSnapshot(Path sourceDatabase, Path targetDatabase) {
        try {
            Files.createDirectories(targetDatabase.getParent());
            Files.deleteIfExists(targetDatabase);
            SQLiteDataSource dataSource = dataSource(sourceDatabase, false);
            try (Connection connection = dataSource.getConnection()) {
                SQLiteConnection sqliteConnection = connection.unwrap(SQLiteConnection.class);
                int result = sqliteConnection.getDatabase().backup("main", targetDatabase.toString(), null);
                if (result != SQLITE_OK) {
                    throw new DatabaseMigrationException("SQLite backup failed with code: " + result);
                }
            }
        } catch (IOException | SQLException ex) {
            throw new DatabaseMigrationException("Failed to create SQLite snapshot", ex);
        }
    }

    /**
     * 在没有活动连接时用已验证快照替换数据库文件。
     *
     * <p>先写同目录临时文件再原子移动；同时清理旧 WAL/SHM，避免旧事务日志污染新数据库。</p>
     *
     * @param snapshotDatabase 已验证快照
     * @param targetDatabase 当前数据库路径
     */
    public void replaceDatabase(Path snapshotDatabase, Path targetDatabase) {
        Path temporary = targetDatabase.resolveSibling(targetDatabase.getFileName() + ".replacement");
        try {
            Files.createDirectories(targetDatabase.getParent());
            Files.copy(snapshotDatabase, temporary, StandardCopyOption.REPLACE_EXISTING);
            deleteSidecars(targetDatabase);
            moveAtomically(temporary, targetDatabase);
        } catch (IOException ex) {
            throw new DatabaseMigrationException("Failed to replace SQLite database", ex);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 失败路径保留原始异常；下次启动会覆盖同名 replacement 文件。
            }
        }
    }

    /**
     * 执行 SQLite 文件级和外键完整性检查。
     *
     * @param databasePath 待验证数据库
     */
    public void validate(Path databasePath) {
        SQLiteDataSource dataSource = dataSource(databasePath, true);
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA trusted_schema=OFF");
            try (ResultSet resultSet = statement.executeQuery("PRAGMA quick_check")) {
                if (!resultSet.next() || !"ok".equalsIgnoreCase(resultSet.getString(1))) {
                    throw new DatabaseMigrationException("SQLite quick_check failed");
                }
            }
            try (ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
                if (resultSet.next()) {
                    throw new DatabaseMigrationException("SQLite foreign_key_check failed");
                }
            }
        } catch (SQLException ex) {
            throw new DatabaseMigrationException("Failed to validate SQLite database", ex);
        }
    }

    public SQLiteDataSource dataSource(Path databasePath, boolean readOnly) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.enableLoadExtension(false);
        config.setBusyTimeout(30_000);
        config.setReadOnly(readOnly);
        SQLiteDataSource dataSource = new SQLiteDataSource(config);
        dataSource.setUrl("jdbc:sqlite:" + databasePath.toAbsolutePath().normalize());
        return dataSource;
    }

    private static void deleteSidecars(Path databasePath) throws IOException {
        Files.deleteIfExists(Path.of(databasePath + "-wal"));
        Files.deleteIfExists(Path.of(databasePath + "-shm"));
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
