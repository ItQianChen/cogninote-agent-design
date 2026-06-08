package com.itqianchen.agentdesign.mapper.schema;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface DatabaseSchemaMapper {

    void createKnowledgeFoldersTable();

    void createDocumentsTable();

    void createChunksTable();

    void createLegacyModelConfigTable();

    void createModelConfigsTable();

    void createChatSessionsTable();

    void createChatMessagesTable();

    List<Map<String, Object>> tableInfo(@Param("tableName") String tableName);

    void addColumn(
            @Param("tableName") String tableName,
            @Param("columnName") String columnName,
            @Param("definition") String definition
    );

    void createKnowledgeFoldersPathIndex();

    void createKnowledgeFoldersEnabledIndex();

    void createDocumentsKnowledgeFolderIdIndex();

    void createDocumentsUpdatedAtIndex();

    void createChunksDocumentIdIndex();

    void createModelConfigsRoleIndex();

    void createModelConfigsRoleActiveIndex();

    void createChatSessionsUpdatedAtIndex();

    void createChatMessagesSequenceIndex();

    void createChatMessagesConversationIdIndex();

    void deleteSoftDeletedChatMessages();

    void deleteSoftDeletedChatSessions();

    long countModelConfigs();

    List<Map<String, Object>> findLegacyActiveModelConfig();

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
