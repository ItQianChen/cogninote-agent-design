package com.itqianchen.agentdesign.ingestion;

import com.itqianchen.agentdesign.document.FileType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

@Component
public class PdfDocumentParser implements DocumentParser {

    @Override
    public boolean supports(FileType fileType) {
        return fileType == FileType.PDF;
    }

    @Override
    public ParsedDocument parse(Path path) {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setPageEnd("\f");

            String fullText = stripper.getText(document);
            String[] pageTexts = fullText.split("\f", -1);
            List<ParsedSection> sections = new ArrayList<>();

            for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
                String pageText = pageIndex < pageTexts.length ? pageTexts[pageIndex] : "";
                if (pageText != null && !pageText.isBlank()) {
                    sections.add(new ParsedSection(pageText, null, pageIndex + 1));
                }
            }

            if (sections.isEmpty()) {
                // Empty extraction means the PDF has no usable text layer for this MVP.
                throw new DocumentParseException("PDF has no extractable text layer: " + path);
            }

            return new ParsedDocument(FileType.PDF, sections);
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to parse PDF file: " + path, ex);
        }
    }
}
