package com.itqianchen.agentdesign.support;

import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Registers isolated application storage for a Spring integration test class.
 *
 * <p>The caller owns the class-scoped temporary directory. Keeping ownership in each test class
 * prevents Spring context reuse from silently sharing SQLite or Lucene state across test suites.
 */
public final class TestStorageProperties {

    private TestStorageProperties() {
    }

    public static void register(DynamicPropertyRegistry registry, Path storageRoot) {
        registry.add("app.storage.base-dir", () -> storageRoot.toString());
        registry.add(
                "app.storage.database-path",
                () -> storageRoot.resolve("data/cogninote.db").toString()
        );
    }
}
