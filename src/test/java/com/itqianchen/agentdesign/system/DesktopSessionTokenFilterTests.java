package com.itqianchen.agentdesign.system;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.config.DesktopSessionTokenFilter;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 覆盖桌面模式下本机 API 的临时令牌保护。
 *
 * <p>测试使用 /api/system/status 作为最轻量 API，避免为了鉴权契约触发模型或索引初始化路径。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.desktop.enabled=true",
        "app.desktop.session-token=test-desktop-token",
        "server.address=127.0.0.1"
})
class DesktopSessionTokenFilterTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void rejectsApiRequestWithoutDesktopSessionToken() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void rejectsApiRequestWithWrongDesktopSessionToken() throws Exception {
        mockMvc.perform(get("/api/system/status")
                        .header(DesktopSessionTokenFilter.SESSION_TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success", is(false)))
                .andExpect(jsonPath("$.code", is("UNAUTHORIZED")));
    }

    @Test
    void allowsApiRequestWithDesktopSessionToken() throws Exception {
        mockMvc.perform(get("/api/system/status")
                        .header(DesktopSessionTokenFilter.SESSION_TOKEN_HEADER, "test-desktop-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.desktopMode", is(true)));
    }
}

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.desktop.enabled=false",
        "app.desktop.session-token=test-desktop-token",
        "server.address=127.0.0.1"
})
class DesktopSessionTokenFilterDisabledTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsApiRequestWithoutTokenWhenDesktopModeIsDisabled() throws Exception {
        mockMvc.perform(get("/api/system/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success", is(true)))
                .andExpect(jsonPath("$.data.desktopMode", is(false)));
    }
}
