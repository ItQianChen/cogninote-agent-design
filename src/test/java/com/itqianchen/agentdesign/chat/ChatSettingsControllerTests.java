package com.itqianchen.agentdesign.chat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.repository.settings.AppSettingRepository;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 聊天设置控制器测试。
 * <p>覆盖前端设置页依赖的普通 JSON API，确保追问策略、消息宽度和输入框宽度能持久化回显。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "server.address=127.0.0.1"
})
class ChatSettingsControllerTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @Autowired
    private AppSettingRepository appSettingRepository;

    /**
     * 每个测试前清理本地 SQLite 状态。
     * <p>聊天设置是全局持久化数据，必须避免测试之间互相影响。</p>
     */
    @BeforeEach
    void clearState() {
        databaseCleaner.clearAll();
    }

    /**
     * 默认设置应返回当前版本的布局宽度和 AUTO 模式。
     * <p>未写入 SQLite 且未覆盖环境变量时，后端初始化所有默认设置。</p>
     */
    @Test
    void chatSettingsDefaultToAuto() throws Exception {
        mockMvc.perform(get("/api/chat/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("AUTO"))
                .andExpect(jsonPath("$.data.assistantMessageWidth").value(100))
                .andExpect(jsonPath("$.data.userMessageWidth").value(72))
                .andExpect(jsonPath("$.data.composerWidth").value(100));

        org.assertj.core.api.Assertions.assertThat(
                        appSettingRepository.findValue("chat.query-contextualizer.mode"))
                .contains("AUTO");
        org.assertj.core.api.Assertions.assertThat(appSettingRepository.findValue("chat.assistant-message-width"))
                .contains("100");
        org.assertj.core.api.Assertions.assertThat(appSettingRepository.findValue("chat.user-message-width"))
                .contains("72");
        org.assertj.core.api.Assertions.assertThat(appSettingRepository.findValue("chat.composer-width"))
                .contains("100");
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
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("OFF"))
                .andExpect(jsonPath("$.data.assistantMessageWidth").value(100))
                .andExpect(jsonPath("$.data.userMessageWidth").value(72))
                .andExpect(jsonPath("$.data.composerWidth").value(100));

        mockMvc.perform(get("/api/chat/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.queryContextualizerMode").value("OFF"))
                .andExpect(jsonPath("$.data.assistantMessageWidth").value(100))
                .andExpect(jsonPath("$.data.userMessageWidth").value(72))
                .andExpect(jsonPath("$.data.composerWidth").value(100));
    }

    /**
     * 保存消息宽度后应能独立回显，验证宽度设置和追问策略共用同一份数据库快照。
     */
    @Test
    void chatSettingsPersistMessageWidths() throws Exception {
        mockMvc.perform(put("/api/chat/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryContextualizerMode\":\"AUTO\",\"assistantMessageWidth\":84,\"userMessageWidth\":68,\"composerWidth\":86}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessageWidth").value(84))
                .andExpect(jsonPath("$.data.userMessageWidth").value(68))
                .andExpect(jsonPath("$.data.composerWidth").value(86));

        mockMvc.perform(get("/api/chat/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessageWidth").value(84))
                .andExpect(jsonPath("$.data.userMessageWidth").value(68))
                .andExpect(jsonPath("$.data.composerWidth").value(86));
    }

    /**
     * 缺失输入框宽度字段时不应覆盖已有数据库值，兼容旧客户端请求。
     */
    @Test
    void chatSettingsKeepComposerWidthWhenLegacyRequestOmitsIt() throws Exception {
        appSettingRepository.save("chat.composer-width", "82");

        mockMvc.perform(put("/api/chat/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryContextualizerMode\":\"AUTO\",\"assistantMessageWidth\":90,\"userMessageWidth\":70}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.composerWidth").value(82));

        org.assertj.core.api.Assertions.assertThat(appSettingRepository.findValue("chat.composer-width"))
                .contains("82");
    }

    /**
     * 宽度超出接口契约时应拒绝请求，防止非法 CSS 值进入设置表。
     */
    @Test
    void chatSettingsRejectMessageWidthsOutsideRange() throws Exception {
        mockMvc.perform(put("/api/chat/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"queryContextualizerMode\":\"AUTO\",\"assistantMessageWidth\":49,\"userMessageWidth\":101,\"composerWidth\":49}"))
                .andExpect(status().isBadRequest());
    }
}
