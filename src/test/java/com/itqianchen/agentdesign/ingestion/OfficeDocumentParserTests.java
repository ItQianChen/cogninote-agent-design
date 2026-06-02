package com.itqianchen.agentdesign.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfficeDocumentParserTests {

    @TempDir
    private Path tempDir;

    @Test
    void docxParserExtractsParagraphText() throws Exception {
        Path docx = tempDir.resolve("note.docx");
        writeDocx(docx, "Docx paragraph");

        ParsedDocument parsedDocument = new DocxDocumentParser().parse(docx);

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
    void emptyPdfFailsAsNoTextLayer() throws Exception {
        Path pdf = tempDir.resolve("empty.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(pdf.toFile());
        }

        assertThatThrownBy(() -> new PdfDocumentParser().parse(pdf))
                .isInstanceOf(DocumentParseException.class)
                .hasMessageContaining("no extractable text");
    }

    private void writeDocx(Path path, String text) throws IOException {
        try (XWPFDocument document = new XWPFDocument();
             OutputStream outputStream = java.nio.file.Files.newOutputStream(path)) {
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
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    contentStream.newLineAtOffset(64, 700);
                    contentStream.showText(pageText);
                    contentStream.endText();
                }
            }
            document.save(path.toFile());
        }
    }
}
