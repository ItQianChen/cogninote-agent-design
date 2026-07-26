package com.itqianchen.agentdesign.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteDataSource;

/**
 * Creates isolated file-backed SQLite databases from the production Flyway migrations.
 */
public final class DatabaseMigrationTestSupport {

    private DatabaseMigrationTestSupport() {
    }

    public static SQLiteDataSource createMigratedDataSource() {
        try {
            Path database = Files.createTempFile("cogninote-flyway-test-", ".db");
            database.toFile().deleteOnExit();
            SQLiteDataSource dataSource = new SQLiteDataSource();
            dataSource.setUrl("jdbc:sqlite:" + database);
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .cleanDisabled(true)
                    .load()
                    .migrate();
            return dataSource;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create migrated test database", ex);
        }
    }
}
