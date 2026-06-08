package com.itqianchen.agentdesign.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 聊天设置控制器测试。
 * <p>覆盖前端设置页依赖的普通 JSON API，确保追问补全模式能持久化回显。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-chat-settings-controller",
        "app.storage.database-path=target/test-cogninote-chat-settings-controller/cogninote.db",
        "server.address=127.0.0.1"
})
class ChatSettingsControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    /**
     * 每个测试前清理本地 SQLite 状态。
     * <p>聊天设置是全局持久化数据，必须避免测试之间互相影响。</p>
     */
    @BeforeEach
    void clearState() {
        databaseCleaner.clearAll();
    }

    /**
     * 默认设置应返回 AUTO。
     * <p>未写入 SQLite 且未覆盖环境变量时，后端默认采用自动触发策略。</p>
     */
    @Test
    void chatSettingsDefaultToAuto() throws Exception {
        mockMvc.perform(get("/api/chat/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("AUTO"));
    }

    /**
     * 保存追问补全模式后应能回显。
     * <p>这验证前端保存按钮不会只改浏览器本地状态。</p>
     */
    @Test
    void chatSettingsPersistQueryContextualizerMode() throws Exception {
        mockMvc.perform(put("/api/chat/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryContextualizerMode\":\"OFF\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("OFF"));

        mockMvc.perform(get("/api/chat/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("OFF"));
    }
}
