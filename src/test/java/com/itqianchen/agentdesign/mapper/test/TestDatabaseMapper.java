package com.itqianchen.agentdesign.mapper.test;

public interface TestDatabaseMapper {

    void deleteChatMessages();

    void deleteChatSessions();

    void deleteChunks();

    void deleteDocuments();

    void deleteKnowledgeFolders();

    void deleteModelConfigs();

    void deleteLegacyModelConfig();

    String findAnyKnowledgeFolderId();
}
