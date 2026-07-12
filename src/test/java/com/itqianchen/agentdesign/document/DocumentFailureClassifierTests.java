package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrPageProcessingException;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrProgressException;
import com.itqianchen.agentdesign.service.document.DocumentFailureClassifier;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DocumentFailureClassifierTests {

    @Test
    void preservesOcrPageFailureClassificationAndResumeProgress() {
        OcrPageProcessingException pageFailure = new OcrPageProcessingException(
                DocumentFailureStage.OCR,
                DocumentFailureCode.OCR_RENDER_FAILED,
                "PDF 页面渲染失败。",
                "render failed",
                8,
                "MODEL_VISION",
                new IllegalStateException("render failed")
        );
        OcrProgressException progressFailure = new OcrProgressException(pageFailure, 7, 10, 8);

        DocumentFailureResponse failure = DocumentFailureClassifier.classify(
                Path.of("D:/documents/scanned.pdf"),
                DocumentFailureStage.PARSE,
                progressFailure,
                100L
        );

        assertThat(failure.stage()).isEqualTo("OCR");
        assertThat(failure.code()).isEqualTo("OCR_RENDER_FAILED");
        assertThat(failure.pageNumber()).isEqualTo(8);
        assertThat(failure.completedPages()).isEqualTo(7);
        assertThat(failure.totalPages()).isEqualTo(10);
        assertThat(failure.resumePage()).isEqualTo(8);
    }
}
