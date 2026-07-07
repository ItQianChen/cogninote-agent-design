package com.itqianchen.agentdesign.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.ingestion.PdfOcrRequiredException;
import com.itqianchen.agentdesign.domain.support.ingestion.DocDocumentParser;
import com.itqianchen.agentdesign.domain.support.ingestion.DocxDocumentParser;
import com.itqianchen.agentdesign.domain.support.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.domain.support.ingestion.PdfDocumentParser;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeDocumentParserTests {

    @TempDir
    private Path tempDir;

    @Test
    void docParserSupportsOnlyLegacyDoc() {
        DocDocumentParser parser = new DocDocumentParser();

        assertThat(parser.supports(FileType.DOC)).isTrue();
        assertThat(parser.supports(FileType.DOCX)).isFalse();
    }

    @Test
    void documentParserRegistryFindsDocParser() {
        DocDocumentParser parser = new DocDocumentParser();
        DocumentParserRegistry registry = new DocumentParserRegistry(List.of(parser));

        assertThat(registry.parserFor(FileType.DOC)).isSameAs(parser);
    }

    @Test
    void docParserExtractsFixtureText() throws Exception {
        ParsedDocument parsedDocument = new DocDocumentParser().parse(fixture("legacy-note.doc"));

        assertThat(parsedDocument.fileType()).isEqualTo(FileType.DOC);
        assertThat(parsedDocument.sections())
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.heading()).isNull();
                    assertThat(section.pageNumber()).isNull();
                    assertThat(section.content()).contains("This is a line of text.");
                });
        assertThat(parsedDocument.plainText()).contains("This is a line of text.");
    }

    @Test
    void corruptDocFailsWithPathContext() throws Exception {
        Path doc = tempDir.resolve("broken.doc");
        Files.writeString(doc, "not a word binary file");

        assertThatThrownBy(() -> new DocDocumentParser().parse(doc))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("Failed to parse DOC file")
                .hasMessageContaining("broken.doc");
    }

    @Test
    void docxParserDoesNotClaimLegacyDoc() {
        DocxDocumentParser parser = new DocxDocumentParser();

        assertThat(parser.supports(FileType.DOCX)).isTrue();
        assertThat(parser.supports(FileType.DOC)).isFalse();
    }

    @Test
    void docxParserExtractsParagraphText() throws Exception {
        Path docx = tempDir.resolve("note.docx");
        writeDocx(docx, "Docx paragraph");

        ParsedDocument parsedDocument = new DocxDocumentParser().parse(docx);

        assertThat(parsedDocument.fileType()).isEqualTo(FileType.DOCX);
        assertThat(parsedDocument.plainText()).contains("Docx paragraph");
    }

    @Test
    void pdfParserExtractsTextByPage() throws Exception {
        Path pdf = tempDir.resolve("note.pdf");
        writePdf(pdf, "PDF paragraph", "Second page");

        ParsedDocument parsedDocument = new PdfDocumentParser().parse(pdf);

        assertThat(parsedDocument.sections())
                .hasSize(2)
                .satisfiesExactly(
                        section -> {
                            assertThat(section.pageNumber()).isEqualTo(1);
                            assertThat(section.content()).contains("PDF paragraph");
                        },
                        section -> {
                            assertThat(section.pageNumber()).isEqualTo(2);
                            assertThat(section.content()).contains("Second page");
                        }
                );
    }

    @Test
    void pdfParserKeepsTextPagesWhenOtherPagesAreBlank() throws Exception {
        Path pdf = tempDir.resolve("mixed.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDPage textPage = new PDPage();
            document.addPage(textPage);
            writePageText(document, textPage, "Only this page has text");
            document.save(pdf.toFile());
        }

        ParsedDocument parsedDocument = new PdfDocumentParser().parse(pdf);

        assertThat(parsedDocument.sections())
                .singleElement()
                .satisfies(section -> {
                    assertThat(section.pageNumber()).isEqualTo(2);
                    assertThat(section.content()).contains("Only this page has text");
                });
    }

    @Test
    void emptyPdfFailsAsNoTextLayer() throws Exception {
        Path pdf = tempDir.resolve("empty.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> new PdfDocumentParser().parse(pdf))
                .isInstanceOf(PdfOcrRequiredException.class)
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("OCR is required");
    }

    @Test
    void corruptPdfFailsAsGenericParseError() throws Exception {
        Path pdf = tempDir.resolve("corrupt.pdf");
        Files.writeString(pdf, "not a real pdf");

        assertThatThrownBy(() -> new PdfDocumentParser().parse(pdf))
                .isInstanceOf(DocumentParseException.class)
                .isNotInstanceOf(PdfOcrRequiredException.class)
                .hasMessageContaining("Failed to parse PDF file");
    }

    private Path fixture(String name) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/fixtures/" + name));
        return Path.of(resource.toURI());
    }

    private void writeDocx(Path path, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             OutputStream outputStream = Files.newOutputStream(path)) {
            XWPFParagraph paragraph = document.createParagraph();
            paragraph.createRun().setText(text);
            document.write(outputStream);
        }
    }

    private void writePdf(Path path, String... pageTexts) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pageTexts) {
                PDPage page = new PDPage();
                document.addPage(page);
                writePageText(document, page, pageText);
            }
            document.save(path.toFile());
        }
    }

    private void writePageText(PDDocument document, PDPage page, String text) throws IOException {
        try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
            contentStream.beginText();
            contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            contentStream.newLineAtOffset(64, 700);
            contentStream.showText(text);
            contentStream.endText();
        }
    }
}
