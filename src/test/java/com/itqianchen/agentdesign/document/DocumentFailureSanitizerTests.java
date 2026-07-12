package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.service.document.DocumentFailureSanitizer;
import org.junit.jupiter.api.Test;

class DocumentFailureSanitizerTests {

    @Test
    void sanitizesJsonSecretsAndRawImageBase64() {
        String rawImage = "a".repeat(240);
        String detail = "{\"api_key\":\"local-secret\",\"image\":\"" + rawImage + "\"}";

        String sanitized = DocumentFailureSanitizer.sanitize(detail);

        assertThat(sanitized)
                .contains("api_key=[REDACTED]")
                .contains("[BASE64_REDACTED]")
                .doesNotContain("local-secret")
                .doesNotContain(rawImage);
    }
}
