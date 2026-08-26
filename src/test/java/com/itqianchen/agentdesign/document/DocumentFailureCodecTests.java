package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrProgressException;
import com.itqianchen.agentdesign.service.document.DocumentFailureClassifier;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.ocr.OcrProviderException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentFailureCodecTests {

    private final DocumentFailureCodec codec = new DocumentFailureCodec(new ObjectMapper());

    @Test
    void decodesLegacyTwoFieldFailureJson() {
        List<DocumentFailureResponse> failures = codec.decodeFailures(
                "[{\"sourcePath\":\"D:/docs/broken.pdf\",\"message\":\"legacy failure\"}]"
        );

        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure.sourcePath()).isEqualTo("D:/docs/broken.pdf");
            assertThat(failure.stage()).isEqualTo("UNKNOWN");
            assertThat(failure.code()).isEqualTo("LEGACY_FAILURE");
            assertThat(failure.message()).isEqualTo("legacy failure");
        });
    }

    @Test
    void roundTripsStructuredFailureWithoutSecretFields() {
        DocumentFailureResponse failure = DocumentFailureResponse.of(
                "D:/docs/scanned.pdf",
                DocumentFailureStage.MODEL_CALL,
                DocumentFailureCode.MODEL_AUTH_FAILED,
                "视觉模型鉴权失败。",
                "OPENAI_COMPATIBLE / qwen3-vl-plus / HTTP 401",
                "检查 API Key。",
                1780000000000L,
                2,
                "OPENAI_COMPATIBLE",
                "qwen3-vl-plus",
                401,
                "invalid_api_key"
        ).withOcrProgress(1, 3, 2);

        assertThat(codec.decodeFailures(codec.encodeFailures(List.of(failure))))
                .containsExactly(failure);
    }

    @Test
    void classifiesOcrFailureWithDurableResumeProgress() {
        var failure = DocumentFailureClassifier.classify(
                Path.of("D:/docs/scanned.pdf"),
                DocumentFailureStage.PARSE,
                new OcrProgressException(new OcrProviderException("provider failed"), 7, 10, 8),
                1780000000000L
        );

        assertThat(failure.stage()).isEqualTo("MODEL_CALL");
        assertThat(failure.completedPages()).isEqualTo(7);
        assertThat(failure.totalPages()).isEqualTo(10);
        assertThat(failure.resumePage()).isEqualTo(8);
    }
}
