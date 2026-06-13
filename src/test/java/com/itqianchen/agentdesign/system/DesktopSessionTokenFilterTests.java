package com.itqianchen.agentdesign.system;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.itqianchen.agentdesign.config.DesktopSessionTokenFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 覆盖桌面模式下本机 API 的临时令牌保护。
 *
 * <p>测试使用 /api/system/status 作为最轻量 API，避免为了鉴权契约触发模型或索引初始化路径。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-desktop-token",
        "app.storage.database-path=target/test-cogninote-desktop-token/cogninote.db",
        "app.desktop.enabled=true",
        "app.desktop.session-token=test-desktop-token",
        "server.address=127.0.0.1"
})
class DesktopSessionTokenFilterTests {

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
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-desktop-token-disabled",
        "app.storage.database-path=target/test-cogninote-desktop-token-disabled/cogninote.db",
        "app.desktop.enabled=false",
        "app.desktop.session-token=test-desktop-token",
        "server.address=127.0.0.1"
})
class DesktopSessionTokenFilterDisabledTests {

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
