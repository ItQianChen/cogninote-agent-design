package com.itqianchen.agentdesign.service.system;


import com.itqianchen.agentdesign.domain.enums.model.ModelConfigRole;
import com.itqianchen.agentdesign.domain.support.model.ModelConfigDefaults;
import com.itqianchen.agentdesign.mapper.schema.DatabaseSchemaMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化本地 SQLite schema 和基础种子数据。
 *
 * <p>当前版本是新的数据库基线；初始化只面向空库或已是当前结构的库，不再执行测试版历史 schema 迁移。</p>
 */
@Component
public class DatabaseSchemaInitializer implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

    private final DatabaseSchemaMapper databaseSchemaMapper;

    /**
     * 注入 schema 初始化 Mapper。
     *
     * @param databaseSchemaMapper 启动期 DDL 和种子数据 SQL Mapper
     */
    public DatabaseSchemaInitializer(DatabaseSchemaMapper databaseSchemaMapper) {
        this.databaseSchemaMapper = databaseSchemaMapper;
    }

    /**
     * 在应用就绪后初始化 SQLite schema。
     *
     * @param event Spring Boot 应用就绪事件
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        initialize();
    }

    /**
     * 返回最高优先级，保证 schema 先于其他启动清理执行。
     *
     * @return Ordered.HIGHEST_PRECEDENCE
     */
    @Override
    public int getOrder() {
        // Schema 必须先于各业务模块的启动清理运行，避免新表尚未创建就被查询。
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 执行幂等建表、索引创建和默认模型配置初始化。
     */
    public void initialize() {
        databaseSchemaMapper.createKnowledgeFoldersTable();
        databaseSchemaMapper.createDocumentsTable();
        databaseSchemaMapper.createChunksTable();
        databaseSchemaMapper.createModelConfigsTable();
        migrateModelConfigEmbeddingRateLimitColumns();
        databaseSchemaMapper.createChatSessionsTable();
        databaseSchemaMapper.createChatMessagesTable();
        databaseSchemaMapper.createAppSettingsTable();
        databaseSchemaMapper.createKnowledgeFolderRunsTable();
        databaseSchemaMapper.createKnowledgeGraphRunsTable();
        databaseSchemaMapper.createKnowledgeGraphChunkExtractionsTable();
        databaseSchemaMapper.createKnowledgeGraphNodesTable();
        databaseSchemaMapper.createKnowledgeGraphEdgesTable();
        databaseSchemaMapper.createKnowledgeGraphEvidenceTable();
        databaseSchemaMapper.createKnowledgeGraphViewsTable();
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
        databaseSchemaMapper.createKnowledgeGraphNodesScopeCanonicalIndex();
        databaseSchemaMapper.createKnowledgeGraphEdgesScopeIndex();
        databaseSchemaMapper.createKnowledgeGraphEdgesScopeTripleIndex();
        databaseSchemaMapper.createKnowledgeGraphEvidenceNodeIndex();
        databaseSchemaMapper.createKnowledgeGraphEvidenceEdgeIndex();
        databaseSchemaMapper.createKnowledgeGraphEvidenceChunkIndex();
        databaseSchemaMapper.createKnowledgeFolderRunsScopeIndex();
        databaseSchemaMapper.createKnowledgeFolderRunsOperationIndex();
        databaseSchemaMapper.createKnowledgeFolderRunsStatusIndex();
        databaseSchemaMapper.createKnowledgeGraphRunsScopeStatusIndex();
        databaseSchemaMapper.createKnowledgeGraphViewsScopeIndex();
        initializeDefaultModelConfigsIfEmpty();
    }

    /**
     * 为空库写入默认 Chat、Embedding 和 Vision 模型配置。
     */
    private void initializeDefaultModelConfigsIfEmpty() {
        if (databaseSchemaMapper.countModelConfigs() > 0) {
            return;
        }
        long now = System.currentTimeMillis();
        insertInitialModelConfig(
                ModelConfigDefaults.ACTIVE_CHAT_CONFIG_ID,
                ModelConfigRole.CHAT.name(),
                ModelConfigDefaults.PROVIDER.name(),
                ModelConfigDefaults.CHAT_DISPLAY_NAME,
                ModelConfigDefaults.BASE_URL,
                "",
                ModelConfigDefaults.CHAT_MODEL,
                null,
                null,
                null,
                null,
                ModelConfigDefaults.TEMPERATURE,
                ModelConfigDefaults.TOP_K,
                ModelConfigDefaults.CONTEXT_WINDOW_TOKENS,
                now,
                now
        );
        insertInitialModelConfig(
                ModelConfigDefaults.ACTIVE_EMBEDDING_CONFIG_ID,
                ModelConfigRole.EMBEDDING.name(),
                ModelConfigDefaults.PROVIDER.name(),
                ModelConfigDefaults.EMBEDDING_DISPLAY_NAME,
                ModelConfigDefaults.BASE_URL,
                "",
                ModelConfigDefaults.EMBEDDING_MODEL,
                ModelConfigDefaults.EMBEDDING_DIMENSIONS,
                ModelConfigDefaults.EMBEDDING_REQUESTS_PER_MINUTE,
                ModelConfigDefaults.EMBEDDING_TOKENS_PER_MINUTE,
                ModelConfigDefaults.EMBEDDING_BATCH_SIZE,
                null,
                null,
                null,
                now,
                now
        );
        insertInitialModelConfig(
                ModelConfigDefaults.ACTIVE_VISION_CONFIG_ID,
                ModelConfigRole.VISION.name(),
                ModelConfigDefaults.PROVIDER.name(),
                ModelConfigDefaults.VISION_DISPLAY_NAME,
                ModelConfigDefaults.BASE_URL,
                "",
                ModelConfigDefaults.VISION_MODEL,
                null,
                null,
                null,
                null,
                ModelConfigDefaults.VISION_TEMPERATURE,
                null,
                null,
                now,
                now
        );
    }

    /**
     * 插入启动期初始模型配置。
     *
     * @param id 配置 ID
     * @param role 模型角色
     * @param provider Provider 名称
     * @param displayName 展示名称
     * @param baseUrl Base URL
     * @param apiKey API Key
     * @param modelName 模型名称
     * @param embeddingDimensions 向量维度
     * @param embeddingRequestsPerMinute 向量请求 RPM
     * @param embeddingTokensPerMinute 向量输入 TPM
     * @param embeddingBatchSize 向量批量大小
     * @param temperature 温度
     * @param defaultTopK 默认 topK
     * @param contextWindowTokens 上下文窗口 token
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
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
            Integer embeddingRequestsPerMinute,
            Integer embeddingTokensPerMinute,
            Integer embeddingBatchSize,
            Double temperature,
            Integer defaultTopK,
            Integer contextWindowTokens,
            long createdAt,
            long updatedAt
    ) {
        databaseSchemaMapper.insertInitialModelConfig(
                id,
                role,
                provider,
                displayName,
                baseUrl,
                apiKey,
                modelName,
                embeddingDimensions,
                embeddingRequestsPerMinute,
                embeddingTokensPerMinute,
                embeddingBatchSize,
                temperature,
                defaultTopK,
                contextWindowTokens,
                createdAt,
                updatedAt
        );
    }

    /**
     * 为已有本地库补齐 Embedding 限速字段。
     *
     * <p>SQLite 发行版本对 ADD COLUMN IF NOT EXISTS 支持不完全一致，因此用幂等 DDL +
     * duplicate column 容错，保证旧用户启动时不会因为字段已存在而失败。</p>
     */
    private void migrateModelConfigEmbeddingRateLimitColumns() {
        addOptionalColumn("model_configs.embedding_requests_per_minute",
                databaseSchemaMapper::addModelConfigEmbeddingRequestsPerMinuteColumn);
        addOptionalColumn("model_configs.embedding_tokens_per_minute",
                databaseSchemaMapper::addModelConfigEmbeddingTokensPerMinuteColumn);
        addOptionalColumn("model_configs.embedding_batch_size",
                databaseSchemaMapper::addModelConfigEmbeddingBatchSizeColumn);
    }

    private void addOptionalColumn(String column, Runnable migration) {
        try {
            migration.run();
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (message.contains("duplicate column")) {
                log.debug("database_optional_column_exists column={}", column);
                return;
            }
            throw ex;
        }
    }
}
