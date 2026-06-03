package com.itqianchen.agentdesign.metadata;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private final JdbcTemplate jdbcTemplate;

    public DatabaseSchemaInitializer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initialize();
    }

    public void initialize() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY,
                    source_path TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    file_type TEXT NOT NULL,
                    file_size INTEGER,
                    last_modified INTEGER,
                    content_hash TEXT,
                    status TEXT NOT NULL,
                    indexed_at INTEGER,
                    created_at INTEGER,
                    updated_at INTEGER
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    document_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    content TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    page_number INTEGER,
                    heading TEXT,
                    token_count INTEGER,
                    created_at INTEGER,
                    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_config (
                    id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    api_key TEXT,
                    chat_model TEXT NOT NULL,
                    embedding_model TEXT NOT NULL,
                    embedding_dimensions INTEGER NOT NULL,
                    temperature REAL NOT NULL,
                    top_k INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        // 旧版本数据库中已经存在 model_config 时，CREATE TABLE 不会补列。
        // 这里显式做轻量迁移，保证用户本地 SQLite 能跟随阶段升级继续使用。
        addColumnIfMissing("model_config", "display_name", "TEXT NOT NULL DEFAULT 'DashScope'");
        addColumnIfMissing("model_config", "base_url",
                "TEXT NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/api/v1'");
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS model_configs (
                    id TEXT PRIMARY KEY,
                    role TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    base_url TEXT NOT NULL,
                    api_key TEXT,
                    model_name TEXT NOT NULL,
                    embedding_dimensions INTEGER,
                    temperature REAL,
                    default_top_k INTEGER,
                    is_active INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """);
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_documents_updated_at ON documents(updated_at DESC)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chunks_document_id ON chunks(document_id)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_model_configs_role ON model_configs(role)");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_model_configs_role_active ON model_configs(role, is_active)");
        migrateLegacyModelConfigIfNeeded();
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + tableName + ")");
        boolean exists = columns.stream()
                .map(column -> String.valueOf(column.get("name")))
                .anyMatch(columnName::equalsIgnoreCase);
        if (!exists) {
            jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private void migrateLegacyModelConfigIfNeeded() {
        Number existingRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM model_configs", Number.class);
        if (existingRows != null && existingRows.longValue() > 0) {
            return;
        }

        List<Map<String, Object>> legacyRows = jdbcTemplate.queryForList("""
                SELECT provider, display_name, base_url, api_key, chat_model, embedding_model,
                       embedding_dimensions, temperature, top_k, created_at, updated_at
                FROM model_config
                WHERE id = 'active'
                """);
        Map<String, Object> legacy = legacyRows.isEmpty() ? Map.of() : legacyRows.getFirst();
        long now = System.currentTimeMillis();
        long createdAt = longValue(legacy.get("created_at"), now);
        long updatedAt = longValue(legacy.get("updated_at"), now);
        String provider = textValue(legacy.get("provider"), "DASHSCOPE");
        String baseUrl = textValue(legacy.get("base_url"), "https://dashscope.aliyuncs.com/api/v1");
        String apiKey = textValue(legacy.get("api_key"), "");

        /*
         * Phase 8 把“一个 active 配置同时管 Chat 和 Embedding”拆成两个 active 配置。
         * 迁移只在新表为空时执行，避免用户后续新建的多配置被旧表覆盖。
         */
        insertInitialModelConfig(
                "active-chat",
                "CHAT",
                provider,
                textValue(legacy.get("display_name"), "DashScope Chat"),
                baseUrl,
                apiKey,
                textValue(legacy.get("chat_model"), "qwen-plus"),
                null,
                doubleObjectValue(legacy.get("temperature"), 0.7),
                intObjectValue(legacy.get("top_k"), 8),
                createdAt,
                updatedAt
        );
        insertInitialModelConfig(
                "active-embedding",
                "EMBEDDING",
                provider,
                textValue(legacy.get("display_name"), "DashScope Embedding"),
                baseUrl,
                apiKey,
                textValue(legacy.get("embedding_model"), "text-embedding-v4"),
                intObjectValue(legacy.get("embedding_dimensions"), 1024),
                null,
                null,
                createdAt,
                updatedAt
        );
    }

    private void insertInitialModelConfig(
            String id,
            String role,
            String provider,
            String displayName,
            String baseUrl,
            String apiKey,
            String modelName,
            Integer embeddingDimensions,
            Double temperature,
            Integer defaultTopK,
            long createdAt,
            long updatedAt
    ) {
        jdbcTemplate.update("""
                        INSERT INTO model_configs (
                            id, role, provider, display_name, base_url, api_key, model_name,
                            embedding_dimensions, temperature, default_top_k, is_active, created_at, updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                        """,
                id == null || id.isBlank() ? UUID.randomUUID().toString() : id,
                role,
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                embeddingDimensions,
                temperature,
                defaultTopK,
                createdAt,
                updatedAt
        );
    }

    private static String textValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    private static long longValue(Object value, long defaultValue) {
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    private static Integer intObjectValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private static Double doubleObjectValue(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }
}


