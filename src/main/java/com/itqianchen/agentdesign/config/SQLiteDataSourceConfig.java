package com.itqianchen.agentdesign.config;

import com.itqianchen.agentdesign.service.system.AppStorageInitializer;
import com.itqianchen.agentdesign.service.system.DatabaseMigrationService;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.sqlite.SQLiteConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite Data Source 配置 集中维护 业务 相关的 Spring 配置。
 * <p>这里的 Bean 或扫描配置会影响应用启动阶段的基础设施装配。</p>
 */
@Configuration
public class SQLiteDataSourceConfig {

    private static final int SQLITE_POOL_SIZE = 4;
    private static final int SQLITE_MIN_IDLE = 1;
    private static final int SQLITE_BUSY_TIMEOUT_MS = 30_000;

    private final AppStorageInitializer storageInitializer;
    private final DatabaseMigrationService migrationService;

    /**
     * 注入应用存储初始化器。
     *
     * @param storageInitializer 应用存储初始化器
     */
    public SQLiteDataSourceConfig(
            AppStorageInitializer storageInitializer,
            DatabaseMigrationService migrationService
    ) {
        this.storageInitializer = storageInitializer;
        this.migrationService = migrationService;
    }

    /**
     * 创建 SQLite DataSource。
     *
     * @return SQLite 数据源
     */
    @Bean
    public DataSource dataSource() {
        // Hikari 创建 DataSource 时可能立刻打开 SQLite 文件，早于 ApplicationReadyEvent。
        // 因此这里必须先确保 data 目录存在，避免首次启动因为数据库路径不存在失败。
        storageInitializer.ensureInitialized();
        migrationService.migrateBeforeConnectionPool();

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + migrationService.businessDatabasePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setDataSourceProperties(sqliteProperties());
        dataSource.setMaximumPoolSize(SQLITE_POOL_SIZE);
        dataSource.setMinimumIdle(SQLITE_MIN_IDLE);
        dataSource.setPoolName("CogniNoteSQLitePool");
        return dataSource;
    }

    private static java.util.Properties sqliteProperties() {
        SQLiteConfig config = new SQLiteConfig();
        /*
         * 维护任务会串行写 SQLite，但健康页、队列页和 SSE 快照会并发读。
         * WAL + busy_timeout 让短读请求不再因为后台短写入瞬间失败或长期等待连接。
         */
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setBusyTimeout(SQLITE_BUSY_TIMEOUT_MS);
        config.enforceForeignKeys(true);
        config.enableLoadExtension(false);
        return config.toProperties();
    }
}


