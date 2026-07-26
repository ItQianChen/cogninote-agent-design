package com.itqianchen.agentdesign.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * 覆盖系统状态响应的对外契约。
 *
 * <p>状态页依赖固定 appName、version、dataDir 和 desktopMode 字段，测试防止启动配置变更破坏前端展示。</p>
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        "app.version=test-backend-version",
        "server.address=127.0.0.1"
})
class SystemStatusControllerTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private com.itqianchen.agentdesign.service.system.SystemStatusService systemStatusService;

    @Test
    void statusReturnsApplicationHealthAndStoragePath() {
        com.itqianchen.agentdesign.domain.dto.system.SystemStatusResponse response = systemStatusService.status();

        assertThat(response.appName()).isEqualTo("知记空间");
        assertThat(response.version()).isEqualTo("test-backend-version");
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.dataDir()).isEqualTo(storageRoot.toAbsolutePath().normalize().toString());
        assertThat(response.desktopMode()).isFalse();
    }
}


