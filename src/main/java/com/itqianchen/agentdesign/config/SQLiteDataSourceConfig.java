package com.itqianchen.agentdesign.config;

import com.itqianchen.agentdesign.storage.AppStorageInitializer;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SQLiteDataSourceConfig {

    private static final int SQLITE_POOL_SIZE = 1;

    private final AppStorageInitializer storageInitializer;

    public SQLiteDataSourceConfig(AppStorageInitializer storageInitializer) {
        this.storageInitializer = storageInitializer;
    }

    @Bean
    public DataSource dataSource() {
        // Hikari may open the SQLite file while creating the DataSource, before ApplicationReadyEvent fires.
        storageInitializer.ensureInitialized();

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:" + storageInitializer.appStorage().databasePath());
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(SQLITE_POOL_SIZE);
        dataSource.setMinimumIdle(SQLITE_POOL_SIZE);
        dataSource.setPoolName("CogniNoteSQLitePool");
        return dataSource;
    }
}
