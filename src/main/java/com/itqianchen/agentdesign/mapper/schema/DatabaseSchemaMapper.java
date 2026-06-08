package com.itqianchen.agentdesign.mapper.schema;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * Database Schema Mapper 声明 数据库元数据 相关的 MyBatis SQL 操作。
 * <p>方法签名需要和注解 SQL、数据库表结构保持一致。</p>
 */
public interface DatabaseSchemaMapper {

    /**
     * 创建 create Knowledge Folders Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createKnowledgeFoldersTable();

    /**
     * 创建 create Documents Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createDocumentsTable();

    /**
     * 创建 create Chunks Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChunksTable();

    /**
     * 创建 create Legacy Model 配置 Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createLegacyModelConfigTable();

    /**
     * 创建 create Model Configs Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createModelConfigsTable();

    /**
     * 创建 create Chat Sessions Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChatSessionsTable();

    /**
     * 创建 create Chat Messages Table 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChatMessagesTable();

    /**
     * 创建应用设置表。
     * <p>表结构采用 key-value，适合保存不归属于单个模型或会话的全局聊天设置。</p>
     */
    void createAppSettingsTable();

    /**
     * 执行 数据库元数据 中的 table Info 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    List<Map<String, Object>> tableInfo(@Param("tableName") String tableName);

    /**
     * 执行 数据库元数据 中的 add Column 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    void addColumn(
            @Param("tableName") String tableName,
            @Param("columnName") String columnName,
            @Param("definition") String definition
    );

    /**
     * 创建 create Knowledge Folders Path Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createKnowledgeFoldersPathIndex();

    /**
     * 创建 create Knowledge Folders Enabled Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createKnowledgeFoldersEnabledIndex();

    /**
     * 创建 create Documents Knowledge Folder Id Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createDocumentsKnowledgeFolderIdIndex();

    /**
     * 创建 create Documents Updated At Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createDocumentsUpdatedAtIndex();

    /**
     * 创建 create Chunks Document Id Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChunksDocumentIdIndex();

    /**
     * 创建 create Model Configs Role Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createModelConfigsRoleIndex();

    /**
     * 创建 create Model Configs Role Active Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createModelConfigsRoleActiveIndex();

    /**
     * 创建 create Chat Sessions Updated At Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChatSessionsUpdatedAtIndex();

    /**
     * 创建 create Chat Messages Sequence Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChatMessagesSequenceIndex();

    /**
     * 创建 create Chat Messages Conversation Id Index 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void createChatMessagesConversationIdIndex();

    /**
     * 删除 delete Soft Deleted Chat Messages 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteSoftDeletedChatMessages();

    /**
     * 删除 delete Soft Deleted Chat Sessions 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteSoftDeletedChatSessions();

    /**
     * 执行 数据库元数据 中的 count Model Configs 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    long countModelConfigs();

    /**
     * 读取 find Legacy Active Model 配置 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    List<Map<String, Object>> findLegacyActiveModelConfig();

    /**
     * 创建 insert Initial Model 配置 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void insertInitialModelConfig(
            @Param("id") String id,
            @Param("role") String role,
            @Param("provider") String provider,
            @Param("displayName") String displayName,
            @Param("baseUrl") String baseUrl,
            @Param("apiKey") String apiKey,
            @Param("modelName") String modelName,
            @Param("embeddingDimensions") Integer embeddingDimensions,
            @Param("temperature") Double temperature,
            @Param("defaultTopK") Integer defaultTopK,
            @Param("contextWindowTokens") Integer contextWindowTokens,
            @Param("createdAt") long createdAt,
            @Param("updatedAt") long updatedAt
    );
}
