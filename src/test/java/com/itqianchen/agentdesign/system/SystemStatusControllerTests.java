package com.itqianchen.agentdesign.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-data",
        "server.address=127.0.0.1"
})
class SystemStatusControllerTests {

    @Autowired
    private com.itqianchen.agentdesign.service.system.SystemStatusService systemStatusService;

    @Test
    void statusReturnsApplicationHealthAndStoragePath() {
        com.itqianchen.agentdesign.dto.system.SystemStatusResponse response = systemStatusService.status();

        assertThat(response.appName()).isEqualTo("CogniNote Agent");
        assertThat(response.version()).isEqualTo("0.0.1-SNAPSHOT");
        assertThat(response.status()).isEqualTo("UP");
        assertThat(response.dataDir()).contains("target");
        assertThat(response.desktopMode()).isFalse();
    }
}


