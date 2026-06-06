package com.itqianchen.agentdesign.metadata;

import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Map<String, String> ALLOWED_COLUMN_MIGRATIONS = Map.of(
            "documents.knowledge_folder_id", "TEXT",
            "model_config.display_name", "TEXT NOT NULL DEFAULT 'DashScope'",
            "model_config.base_url", "TEXT NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/api/v1'",
            "chat_messages.agent_type", "TEXT"
    );

    private final DatabaseSchemaMapper databaseSchemaMapper;

    public DatabaseSchemaInitializer(DatabaseSchemaMapper databaseSchemaMapper) {
        this.databaseSchemaMapper = databaseSchemaMapper;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initialize();
    }

    public void initialize() {
        databaseSchemaMapper.createKnowledgeFoldersTable();
        databaseSchemaMapper.createDocumentsTable();
        databaseSchemaMapper.createChunksTable();
        databaseSchemaMapper.createLegacyModelConfigTable();
        // 旧版本数据库中已经存在 model_config 时，CREATE TABLE 不会补列。
        // 这里显式做轻量迁移，保证用户本地 SQLite 能跟随阶段升级继续使用。
        addColumnIfMissing("documents", "knowledge_folder_id", "TEXT");
        addColumnIfMissing("model_config", "display_name", "TEXT NOT NULL DEFAULT 'DashScope'");
        addColumnIfMissing("model_config", "base_url",
                "TEXT NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/api/v1'");
        databaseSchemaMapper.createModelConfigsTable();
        databaseSchemaMapper.createChatSessionsTable();
        databaseSchemaMapper.createChatMessagesTable();
        addColumnIfMissing("chat_messages", "agent_type", "TEXT");
        databaseSchemaMapper.createKnowledgeFoldersPathIndex();
        databaseSchemaMapper.createKnowledgeFoldersEnabledIndex();
        databaseSchemaMapper.createDocumentsKnowledgeFolderIdIndex();
        databaseSchemaMapper.createDocumentsUpdatedAtIndex();
        databaseSchemaMapper.createChunksDocumentIdIndex();
        databaseSchemaMapper.createModelConfigsRoleIndex();
        databaseSchemaMapper.createModelConfigsRoleActiveIndex();
        databaseSchemaMapper.createChatSessionsUpdatedAtIndex();
        databaseSchemaMapper.createChatMessagesSequenceIndex();
        databaseSchemaMapper.createChatMessagesConversationIdIndex();
        migrateLegacyModelConfigIfNeeded();
    }

    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        String migrationKey = tableName + "." + columnName;
        String allowedDefinition = ALLOWED_COLUMN_MIGRATIONS.get(migrationKey);
        if (!definition.equals(allowedDefinition)) {
            throw new IllegalArgumentException("Unsupported column migration: " + migrationKey);
        }
        List<Map<String, Object>> columns = databaseSchemaMapper.tableInfo(tableName);
        boolean exists = columns.stream()
                .map(DatabaseSchemaInitializer::sqliteColumnName)
                .anyMatch(existingColumn -> existingColumn != null && columnName.equalsIgnoreCase(existingColumn));
        if (!exists) {
            databaseSchemaMapper.addColumn(tableName, columnName, definition);
        }
    }

    private static String sqliteColumnName(Map<String, Object> column) {
        for (Map.Entry<String, Object> entry : column.entrySet()) {
            if ("name".equalsIgnoreCase(entry.getKey())) {
                Object value = entry.getValue();
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private void migrateLegacyModelConfigIfNeeded() {
        if (databaseSchemaMapper.countModelConfigs() > 0) {
            return;
        }

        List<Map<String, Object>> legacyRows = databaseSchemaMapper.findLegacyActiveModelConfig();
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
        databaseSchemaMapper.insertInitialModelConfig(
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
