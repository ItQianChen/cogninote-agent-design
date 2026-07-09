package com.itqianchen.agentdesign.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.dto.ocr.OcrTestResponse;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.enums.ocr.OcrProvider;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.support.ingestion.PdfDocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.service.ocr.OcrEngine;
import com.itqianchen.agentdesign.service.ocr.OcrEngineRegistry;
import com.itqianchen.agentdesign.service.ocr.OcrPageImage;
import com.itqianchen.agentdesign.service.ocr.OcrSettingsSnapshot;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
    void blankPdfWithOcrEnabledProducesPageSections() throws Exception {
        Path pdf = tempDir.resolve("ocr.pdf");
        writeBlankPdf(pdf);
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
    void blankPdfWithOcrEnabledButEmptyOutputFailsAsParseError() throws Exception {
        Path pdf = tempDir.resolve("empty-ocr.pdf");
        writeBlankPdf(pdf);
        PdfDocumentParser parser = parser(enabledSettings(), new FixedOcrEngine(pageImage -> "  "));

        assertThatThrownBy(() -> parser.parse(pdf))
                .isInstanceOf(DocumentParseException.class)
                .isNotInstanceOf(PdfOcrRequiredException.class)
                .hasMessageContaining("PDF OCR produced no usable text");
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
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
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
}
