package com.itqianchen.agentdesign.mapper.test;

import org.apache.ibatis.annotations.Param;

/**
 * 测试 Database Mapper 声明 测试支撑 相关的 MyBatis SQL 操作。
 * <p>方法签名需要和注解 SQL、数据库表结构保持一致。</p>
 */
public interface TestDatabaseMapper {

    /**
     * 删除 delete Chat Messages 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteChatMessages();

    /**
     * 删除 delete Chat Sessions 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteChatSessions();

    /**
     * 创建 insert Soft Deleted Chat Session 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void insertSoftDeletedChatSession(
            @Param("id") String id,
            @Param("title") String title,
            @Param("createdAt") long createdAt,
            @Param("updatedAt") long updatedAt
    );

    /**
     * 创建 insert Chat Message 对应的数据。
     * <p>创建流程集中处理默认值、校验和持久化边界。</p>
     */
    void insertChatMessage(
            @Param("id") String id,
            @Param("conversationId") String conversationId,
            @Param("sequence") int sequence,
            @Param("content") String content,
            @Param("createdAt") long createdAt
    );

    /**
     * 执行 测试支撑 中的 count Chat Sessions By Id 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    int countChatSessionsById(@Param("id") String id);

    /**
     * 执行 测试支撑 中的 count Chat Messages By Conversation Id 步骤。
     * <p>该方法是当前类型内部复用或对外暴露的明确业务边界。</p>
     */
    int countChatMessagesByConversationId(@Param("conversationId") String conversationId);

    /**
     * 删除 delete Chunks 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteChunks();

    /**
     * 删除 delete Documents 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteDocuments();

    /**
     * 删除 delete Knowledge Folders 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteKnowledgeFolders();

    /**
     * 删除 delete Model Configs 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteModelConfigs();

    /**
     * 删除 delete Legacy Model 配置 对应的数据。
     * <p>删除时同步处理关联状态，避免调用方遗漏清理步骤。</p>
     */
    void deleteLegacyModelConfig();

    /**
     * 删除 delete App Settings 对应的数据。
     * <p>删除全局设置，避免测试之间共享持久化开关。</p>
     */
    void deleteAppSettings();

    /**
     * 读取 find Any Knowledge Folder Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    String findAnyKnowledgeFolderId();
}
