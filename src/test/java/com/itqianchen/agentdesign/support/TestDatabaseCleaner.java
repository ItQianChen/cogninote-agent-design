package com.itqianchen.agentdesign.support;

import com.itqianchen.agentdesign.mapper.test.TestDatabaseMapper;
import org.springframework.stereotype.Component;

/**
 * 测试 Database Cleaner 承担 测试支撑 模块的主要职责。
 * <p>注释说明维护边界，不改变现有运行逻辑。</p>
 */
@Component
public class TestDatabaseCleaner {

    private final TestDatabaseMapper testDatabaseMapper;

    /**
     * 注入 TestDatabaseCleaner 运行所需的协作者。
     * <p>依赖由 Spring 或测试环境统一提供，构造器本身不做业务副作用。</p>
     */
    public TestDatabaseCleaner(TestDatabaseMapper testDatabaseMapper) {
        this.testDatabaseMapper = testDatabaseMapper;
    }

    /**
     * 清理 clear All 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearAll() {
        /**
         * 清理 clear Chat 对应的数据。
         * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
         */
        clearChat();
        /**
         * 清理 clear Documents 对应的数据。
         * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
         */
        clearDocuments();
        /**
         * 清理 clear Knowledge Folders 对应的数据。
         * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
         */
        clearKnowledgeFolders();
        /**
         * 清理全局应用设置，避免某个测试保存的聊天开关影响下一个测试。
         */
        testDatabaseMapper.deleteAppSettings();
        /**
         * 清理 clear Model Configs 对应的数据。
         * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
         */
        clearModelConfigs();
    }

    /**
     * 清理 clear Chat 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearChat() {
        testDatabaseMapper.deleteChatMessages();
        testDatabaseMapper.deleteChatSessions();
    }

    /**
     * 清理 clear Documents 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearDocuments() {
        testDatabaseMapper.deleteChunks();
        testDatabaseMapper.deleteDocuments();
    }

    /**
     * 清理 clear Knowledge Folders 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearKnowledgeFolders() {
        testDatabaseMapper.deleteKnowledgeFolders();
    }

    /**
     * 清理 clear Model Configs 对应的数据。
     * <p>清理只移除目标内容，保留会话或模块继续运行所需的外壳状态。</p>
     */
    public void clearModelConfigs() {
        testDatabaseMapper.deleteModelConfigs();
        testDatabaseMapper.deleteLegacyModelConfig();
    }

    /**
     * 读取 find Any Knowledge Folder Id 对应的数据。
     * <p>缺失、空值和兼容兜底由该方法统一处理。</p>
     */
    public String findAnyKnowledgeFolderId() {
        return testDatabaseMapper.findAnyKnowledgeFolderId();
    }
}
