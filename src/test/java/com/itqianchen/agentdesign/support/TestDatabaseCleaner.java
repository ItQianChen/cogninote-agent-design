package com.itqianchen.agentdesign.support;

import com.itqianchen.agentdesign.mapper.test.TestDatabaseMapper;
import org.springframework.stereotype.Component;

@Component
public class TestDatabaseCleaner {

    private final TestDatabaseMapper testDatabaseMapper;

    public TestDatabaseCleaner(TestDatabaseMapper testDatabaseMapper) {
        this.testDatabaseMapper = testDatabaseMapper;
    }

    public void clearAll() {
        clearChat();
        clearDocuments();
        clearKnowledgeFolders();
        clearModelConfigs();
    }

    public void clearChat() {
        testDatabaseMapper.deleteChatMessages();
        testDatabaseMapper.deleteChatSessions();
    }

    public void clearDocuments() {
        testDatabaseMapper.deleteChunks();
        testDatabaseMapper.deleteDocuments();
    }

    public void clearKnowledgeFolders() {
        testDatabaseMapper.deleteKnowledgeFolders();
    }

    public void clearModelConfigs() {
        testDatabaseMapper.deleteModelConfigs();
        testDatabaseMapper.deleteLegacyModelConfig();
    }

    public String findAnyKnowledgeFolderId() {
        return testDatabaseMapper.findAnyKnowledgeFolderId();
    }
}
