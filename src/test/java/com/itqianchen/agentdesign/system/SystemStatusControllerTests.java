package com.itqianchen.agentdesign.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * 覆盖系统状态响应的对外契约。
 *
 * <p>状态页依赖固定 appName、version、dataDir 和 desktopMode 字段，测试防止启动配置变更破坏前端展示。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-data",
        "app.version=test-backend-version",
        "server.address=127.0.0.1"
})
class SystemStatusControllerTests {

    @Autowired
    private com.itqianchen.agentdesign.service.system.SystemStatusService systemStatusService;

    @Test
    void statusReturnsApplicationHealthAndStoragePath() {
        com.itqianchen.agentdesign.dto.system.SystemStatusResponse response = systemStatusService.status();

        assertThat(response.appName()).isEqualTo("知记空间");
        assertThat(response.version()).isEqualTo("test-backend-version");
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.dataDir()).contains("target");
        assertThat(response.desktopMode()).isFalse();
    }
}


