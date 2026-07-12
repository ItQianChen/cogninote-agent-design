package com.itqianchen.agentdesign.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrPageProcessingException;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.exception.ingestion.OcrProgressException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParseCheckpoint;
import com.itqianchen.agentdesign.domain.support.ingestion.PdfDocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentParseRequest;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.service.ocr.OcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrEngineRegistry;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfDocumentParserTests {

    @TempDir
    private Path tempDir;

    @Test
    void textPdfDoesNotTriggerOcr() throws Exception {
        Path pdf = tempDir.resolve("text.pdf");
        writeTextPdf(pdf, "Text layer wins");
        FixedOcrEngine engine = new FixedOcrEngine(pageImage -> {
            throw new AssertionError("OCR must not run for text-layer PDF");
        });
        PdfDocumentParser parser = parser(enabledSettings(), engine);

        ParsedDocument parsedDocument = parser.parse(pdf);

        assertThat(parsedDocument.fileType()).isEqualTo(FileType.PDF);
        assertThat(parsedDocument.plainText()).contains("Text layer wins");
    }

    @Test
    void blankPdfWithoutOcrStillRequiresOcr() throws Exception {
        Path pdf = tempDir.resolve("blank.pdf");
        writeBlankPdf(pdf);

        assertThatThrownBy(() -> new PdfDocumentParser().parse(pdf))
                .isInstanceOf(PdfOcrRequiredException.class)
                .hasMessageContaining("OCR is required");
    }

    @Test
    void imageOnlyPdfWithOcrEnabledProducesPageSections() throws Exception {
        Path pdf = tempDir.resolve("ocr.pdf");
        writeImageOnlyPdf(pdf, 1);
        PdfDocumentParser parser = parser(enabledSettings(),
                new FixedOcrEngine(pageImage -> "OCR page " + pageImage.pageNumber() + " token"));

        ParsedDocument parsedDocument = parser.parse(pdf);

        assertThat(parsedDocument.sections())
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.pageNumber()).isEqualTo(1);
                    assertThat(section.content()).contains("OCR page 1 token");
                });
    }

    @Test
    void imageOnlyPdfWithEmptyModelOutputRemainsRetryable() throws Exception {
        Path pdf = tempDir.resolve("empty-ocr.pdf");
        writeImageOnlyPdf(pdf, 1);
        PdfDocumentParser parser = parser(enabledSettings(), new FixedOcrEngine(pageImage -> "  "));

        assertThatThrownBy(() -> parser.parse(pdf))
                .isInstanceOf(DocumentParseException.class)
                .isNotInstanceOf(PdfOcrRequiredException.class)
                .isInstanceOf(OcrPageProcessingException.class)
                .hasMessageContaining("没有返回可用文字");
    }

    @Test
    void durableCheckpointResumesFromFirstFailedPage() throws Exception {
        Path pdf = tempDir.resolve("resume.pdf");
        writeImageOnlyPdf(pdf, 3);
        List<Integer> calls = new ArrayList<>();
        AtomicBoolean failPageTwoOnce = new AtomicBoolean(true);
        FixedOcrEngine engine = new FixedOcrEngine(pageImage -> {
            calls.add(pageImage.pageNumber());
            if (pageImage.pageNumber() == 2 && failPageTwoOnce.getAndSet(false)) {
                throw new IllegalStateException("temporary model failure");
            }
            return "OCR page " + pageImage.pageNumber();
        });
        PdfDocumentParser parser = parser(enabledSettings(), engine);
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();

        assertThatThrownBy(() -> parser.parse(new DocumentParseRequest(pdf, checkpoint)))
                .isInstanceOfSatisfying(OcrProgressException.class, failure -> {
                    assertThat(failure.completedPages()).isEqualTo(1);
                    assertThat(failure.totalPages()).isEqualTo(3);
                    assertThat(failure.resumePage()).isEqualTo(2);
                });

        ParsedDocument resumed = parser.parse(new DocumentParseRequest(pdf, checkpoint));

        assertThat(calls).containsExactly(1, 2, 2, 3);
        assertThat(resumed.sections()).extracting(section -> section.pageNumber())
                .containsExactly(1, 2, 3);
        assertThat(checkpoint.savedSections).hasSize(3);
    }

    @Test
    void durableCheckpointDoesNotRepeatConfirmedBlankPage() throws Exception {
        Path pdf = tempDir.resolve("blank-checkpoint.pdf");
        writeBlankPdf(pdf);
        List<Integer> calls = new ArrayList<>();
        PdfDocumentParser parser = parser(enabledSettings(), new FixedOcrEngine(pageImage -> {
            calls.add(pageImage.pageNumber());
            return "";
        }));
        InMemoryCheckpoint checkpoint = new InMemoryCheckpoint();

        assertThatThrownBy(() -> parser.parse(new DocumentParseRequest(pdf, checkpoint)))
                .isInstanceOf(OcrProgressException.class)
                .hasMessageContaining("no usable text");
        assertThatThrownBy(() -> parser.parse(new DocumentParseRequest(pdf, checkpoint)))
                .isInstanceOf(OcrProgressException.class)
                .hasMessageContaining("no usable text");

        assertThat(calls).isEmpty();
        assertThat(checkpoint.savedSections).hasSize(1);
    }

    private static PdfDocumentParser parser(OcrSettingsSnapshot settings, OcrEngine engine) {
        return new PdfDocumentParser(() -> settings, new OcrEngineRegistry(List.of(engine)));
    }

    private static OcrSettingsSnapshot enabledSettings() {
        return new OcrSettingsSnapshot(
                true,
                OcrProvider.MODEL_VISION,
                true,
                200,
                20,
                1000
        );
    }

    private void writeBlankPdf(Path path) throws IOException {
        writeBlankPdf(path, 1);
    }

    private void writeBlankPdf(Path path, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < pageCount; page++) {
                document.addPage(new PDPage());
            }
            document.save(path.toFile());
        }
    }

    private void writeImageOnlyPdf(Path path, int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    // 绘制非文本图形，模拟没有 PDF 文本层但页面确实包含视觉内容的扫描件。
                    contentStream.setNonStrokingColor(Color.BLACK);
                    contentStream.addRect(72, 680, 180, 24);
                    contentStream.fill();
                }
            }
            document.save(path.toFile());
        }
    }

    private void writeTextPdf(Path path, String text) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(64, 700);
                contentStream.showText(text);
                contentStream.endText();
            }
            document.save(path.toFile());
        }
    }

    private interface PageRecognizer {
        String recognize(OcrPageImage pageImage);
    }

    private record FixedOcrEngine(PageRecognizer recognizer) implements OcrEngine {

        @Override
        public boolean supports(OcrProvider provider) {
            return provider == OcrProvider.MODEL_VISION;
        }

        @Override
        public String recognize(OcrPageImage pageImage, OcrSettingsSnapshot settings) {
            return recognizer.recognize(pageImage);
        }

        @Override
        public OcrTestResponse test(OcrSettingsSnapshot settings) {
            return new OcrTestResponse(true, "ok", settings.provider(), "vision-model");
        }
    }

    private static final class InMemoryCheckpoint implements DocumentParseCheckpoint {

        private final Map<Integer, ParsedSection> savedSections = new LinkedHashMap<>();
        private String signature;
        private int totalSections;

        @Override
        public boolean durable() {
            return true;
        }

        @Override
        public List<ParsedSection> prepare(String parserSignature, int totalSections) {
            if (!parserSignature.equals(signature) || this.totalSections != totalSections) {
                savedSections.clear();
                signature = parserSignature;
                this.totalSections = totalSections;
            }
            return List.copyOf(savedSections.values());
        }

        @Override
        public void save(ParsedSection section) {
            savedSections.put(section.pageNumber(), section);
        }
    }
}
