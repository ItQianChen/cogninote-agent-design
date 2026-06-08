package com.itqianchen.agentdesign.metadata;

import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

/**
 * Database Schema 初始化器 在应用启动时准备 数据库元数据 资源。
 * <p>启动阶段副作用需要保持幂等，避免重复运行破坏已有数据。</p>
 */
@Component
public class DatabaseSchemaInitializer implements ApplicationListener<ApplicationReadyEvent> {

    private static final Map<String, String> ALLOWED_COLUMN_MIGRATIONS = Map.of(
            "documents.knowledge_folder_id", "TEXT",
            "model_config.display_name", "TEXT NOT NULL DEFAULT 'DashScope'",
            "model_config.base_url", "TEXT NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/api/v1'",
            "model_configs.context_window_tokens", "INTEGER",
            "chat_messages.agent_type", "TEXT"
    );

    private final DatabaseSchemaMapper databaseSchemaMapper;

    /**
     * 注入 DatabaseSchemaInitializer 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public DatabaseSchemaInitializer(DatabaseSchemaMapper databaseSchemaMapper) {
        this.databaseSchemaMapper = databaseSchemaMapper;
    }

    /**
     * 响应 on Application 事件 生命周期事件。
     * <p>常用于应用启动、框架回调或资源初始化场景。</p>
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        /**
         * 执行 数据库元数据 中的 initialize 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        initialize();
    }

    /**
     * 执行 数据库元数据 中的 initialize 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    public void initialize() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createKnowledgeFoldersTable();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createDocumentsTable();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChunksTable();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createLegacyModelConfigTable();
        // 旧版本数据库中已经存在 model_config 时，CREATE TABLE 不会补列。
        // 这里显式做轻量迁移，保证用户本地 SQLite 能跟随阶段升级继续使用。
        /**
         * 执行 数据库元数据 中的 add Column If Missing 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        addColumnIfMissing("documents", "knowledge_folder_id", "TEXT");
        /**
         * 执行 数据库元数据 中的 add Column If Missing 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        addColumnIfMissing("model_config", "display_name", "TEXT NOT NULL DEFAULT 'DashScope'");
        addColumnIfMissing("model_config", "base_url",
                "TEXT NOT NULL DEFAULT 'https://dashscope.aliyuncs.com/api/v1'");
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createModelConfigsTable();
        /**
         * 执行 数据库元数据 中的 add Column If Missing 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        addColumnIfMissing("model_configs", "context_window_tokens", "INTEGER");
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChatSessionsTable();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChatMessagesTable();
        /*
         * 第 23 阶段开始，追问补全策略属于全局聊天设置。
         * 使用独立 key-value 表，避免把非模型参数塞进 model_configs。
         */
        databaseSchemaMapper.createAppSettingsTable();
        /**
         * 执行 数据库元数据 中的 add Column If Missing 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        addColumnIfMissing("chat_messages", "agent_type", "TEXT");
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createKnowledgeFoldersPathIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createKnowledgeFoldersEnabledIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createDocumentsKnowledgeFolderIdIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createDocumentsUpdatedAtIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChunksDocumentIdIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createModelConfigsRoleIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createModelConfigsRoleActiveIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChatSessionsUpdatedAtIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChatMessagesSequenceIndex();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.createChatMessagesConversationIdIndex();
        /**
         * 执行 数据库元数据 中的 cleanup Soft Deleted Chat Sessions 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        cleanupSoftDeletedChatSessions();
        /**
         * 执行 数据库元数据 中的 migrate Legacy Model 配置 If Needed 步骤。
         * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
         */
        migrateLegacyModelConfigIfNeeded();
    }

    /**
     * 执行 数据库元数据 中的 cleanup Soft Deleted Chat Sessions 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private void cleanupSoftDeletedChatSessions() {
        /*
         * 旧版本的“删除会话”只是把 chat_sessions.deleted 置为 1，消息仍留在本地库里。
         * 新版本把用户删除视为销毁操作；启动时顺手清掉历史软删除残留，避免升级后旧数据继续存在。
         */
        databaseSchemaMapper.deleteSoftDeletedChatMessages();
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        databaseSchemaMapper.deleteSoftDeletedChatSessions();
    }

    /**
     * 执行 数据库元数据 中的 add Column If Missing 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private void addColumnIfMissing(String tableName, String columnName, String definition) {
        String migrationKey = tableName + "." + columnName;
        String allowedDefinition = ALLOWED_COLUMN_MIGRATIONS.get(migrationKey);
        if (!definition.equals(allowedDefinition)) {
            throw new IllegalArgumentException("Unsupported column migration: " + migrationKey);
        }
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        List<Map<String, Object>> columns = databaseSchemaMapper.tableInfo(tableName);
        boolean exists = columns.stream()
                .map(DatabaseSchemaInitializer::sqliteColumnName)
                .anyMatch(existingColumn -> existingColumn != null && columnName.equalsIgnoreCase(existingColumn));
        if (!exists) {
            // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
            databaseSchemaMapper.addColumn(tableName, columnName, definition);
        }
    }

    /**
     * 执行 数据库元数据 中的 sqlite Column Name 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static String sqliteColumnName(Map<String, Object> column) {
        for (Map.Entry<String, Object> entry : column.entrySet()) {
            if ("name".equalsIgnoreCase(entry.getKey())) {
                Object value = entry.getValue();
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    /**
     * 执行 数据库元数据 中的 migrate Legacy Model 配置 If Needed 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private void migrateLegacyModelConfigIfNeeded() {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
        if (databaseSchemaMapper.countModelConfigs() > 0) {
            return;
        }

        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
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
                128_000,
                createdAt,
                updatedAt
        );
        /**
         * 创建 insert Initial Model 配置 对应的数据。
         * <p>创建流程集中处理默认值、校验和持久化边界。</p>
         */
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
                null,
                createdAt,
                updatedAt
        );
    }

    /**
     * 创建 insert Initial Model 配置 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
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
            Integer contextWindowTokens,
            long createdAt,
            long updatedAt
    ) {
        // 数据库访问集中经过 Mapper，避免业务层直接拼接 SQL。
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
                contextWindowTokens,
                createdAt,
                updatedAt
        );
    }

    /**
     * 执行 数据库元数据 中的 text Value 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static String textValue(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? defaultValue : text;
    }

    /**
     * 执行 数据库元数据 中的 long Value 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static long longValue(Object value, long defaultValue) {
        return value instanceof Number number ? number.longValue() : defaultValue;
    }

    /**
     * 执行 数据库元数据 中的 int Object Value 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static Integer intObjectValue(Object value, int defaultValue) {
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    /**
     * 执行 数据库元数据 中的 double Object Value 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    private static Double doubleObjectValue(Object value, double defaultValue) {
        return value instanceof Number number ? number.doubleValue() : defaultValue;
    }
}
