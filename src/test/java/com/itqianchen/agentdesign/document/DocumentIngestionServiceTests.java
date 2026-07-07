package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.enums.search.SearchMode;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.search.SearchRequest;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.vo.ingestion.ScannedDocumentFile;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "app.storage.base-dir=target/test-cogninote-ingestion",
        "app.storage.database-path=target/test-cogninote-ingestion/cogninote.db",
        "server.address=127.0.0.1"
})
class DocumentIngestionServiceTests {

    @Autowired
    private DocumentIngestionService ingestionService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeStore knowledgeStore;

    @Autowired
    private TestDatabaseCleaner databaseCleaner;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearDocuments();
        knowledgeStore.rebuildAll();
    }

    @Test
    void ingestFolderParsesMarkdownAndSkipsUnchangedFiles() throws Exception {
        Path note = tempDir.resolve("note.md");
        // 文件系统访问可能抛出 IO 异常，调用方需要保留失败上下文。
        Files.writeString(note, "# Note\n\nThis is a local note.");

        IngestDocumentsResponse first = ingestionService.ingestFolder(tempDir.toString(), true);
        IngestDocumentsResponse second = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(first.scannedCount()).isEqualTo(1);
        assertThat(first.parsedCount()).isEqualTo(1);
        assertThat(first.failedCount()).isZero();
        assertThat(second.skippedCount()).isEqualTo(1);
        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.status()).isEqualTo(DocumentStatus.PARSED);
                    assertThat(document.chunkCount()).isEqualTo(1);
                    assertThat(document.sourcePath()).contains("note.md");
                });
    }

    @Test
    void scanDocumentFilesIncludesPlannedDocumentFormatsWithoutParsing() throws Exception {
        Files.writeString(tempDir.resolve("note.md"), "# Note");
        Files.writeString(tempDir.resolve("note.markdown"), "# Alias");
        Files.writeString(tempDir.resolve("plain.txt"), "Plain text");
        Files.writeString(tempDir.resolve("modern.docx"), "placeholder");
        Files.writeString(tempDir.resolve("legacy.doc"), "placeholder");
        Files.writeString(tempDir.resolve("paper.pdf"), "placeholder");
        Files.writeString(tempDir.resolve("page.html"), "<main>Page</main>");
        Files.writeString(tempDir.resolve("archive.htm"), "<main>Archive</main>");
        Files.writeString(tempDir.resolve("image.png"), "ignored");

        List<String> fileNames = ingestionService.scanDocumentFiles(tempDir.toString(), true).stream()
                .map(ScannedDocumentFile::fileName)
                .toList();

        assertThat(fileNames).containsExactlyInAnyOrder(
                "note.md",
                "note.markdown",
                "plain.txt",
                "modern.docx",
                "legacy.doc",
                "paper.pdf",
                "page.html",
                "archive.htm"
        );
    }

    @Test
    void ingestFolderParsesHtmlAndHtmFilesAndIndexesKeywordSearch() throws Exception {
        Files.writeString(tempDir.resolve("guide.html"), """
                <!doctype html>
                <html>
                  <head><title>HTML Guide</title></head>
                  <body>
                    <main>
                      <h1>Semantic Import</h1>
                      <p>Local HTML content enters the knowledge base.</p>
                    </main>
                  </body>
                </html>
                """);
        Files.writeString(tempDir.resolve("appendix.htm"), """
                <!doctype html>
                <html>
                  <body><main><p>HTM appendix content.</p></main></body>
                </html>
                """);

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(response.scannedCount()).isEqualTo(2);
        assertThat(response.parsedCount()).isEqualTo(2);
        assertThat(response.failedCount()).isZero();
        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .hasSize(2)
                .allSatisfy(document -> {
                    assertThat(document.status()).isEqualTo(DocumentStatus.PARSED);
                    assertThat(document.fileType()).isEqualTo(FileType.HTML);
                    assertThat(document.chunkCount()).isPositive();
                });
        assertThat(knowledgeStore.search(new SearchRequest("Semantic Import", SearchMode.KEYWORD, 5)).hits())
                .anySatisfy(hit -> assertThat(hit.fileName()).isEqualTo("guide.html"));
    }

    @Test
    void ingestFolderParsesDocFileAndIndexesKeywordSearch() throws Exception {
        Files.copy(fixture("legacy-note.doc"), tempDir.resolve("legacy-note.doc"), StandardCopyOption.REPLACE_EXISTING);

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.parsedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isZero();
        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.status()).isEqualTo(DocumentStatus.PARSED);
                    assertThat(document.fileType()).isEqualTo(FileType.DOC);
                    assertThat(document.chunkCount()).isPositive();
                });
        assertThat(knowledgeStore.search(new SearchRequest("line of text", SearchMode.KEYWORD, 5)).hits())
                .anySatisfy(hit -> assertThat(hit.fileName()).isEqualTo("legacy-note.doc"));
    }

    @Test
    void ingestFolderMarksNoTextPdfAsOcrRequired() throws Exception {
        Path pdf = tempDir.resolve("scanned.pdf");
        writeBlankPdf(pdf);

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures())
                .singleElement()
                .satisfies(failure -> assertThat(failure.message()).contains("OCR is required"));
        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.fileType()).isEqualTo(FileType.PDF);
                    assertThat(document.status()).isEqualTo(DocumentStatus.OCR_REQUIRED);
                    assertThat(document.chunkCount()).isZero();
                    assertThat(document.indexedAt()).isNull();
                });
        assertThat(knowledgeStore.search(new SearchRequest("scanned", SearchMode.KEYWORD, 5)).hits()).isEmpty();
    }

    @Test
    void syncKnowledgeFolderMarksNewNoTextPdfAsOcrRequired() throws Exception {
        Path pdf = tempDir.resolve("sync-scanned.pdf");
        writeBlankPdf(pdf);

        IngestDocumentsResponse response = ingestionService.syncKnowledgeFolder(
                "folder-sync-ocr-new",
                tempDir.toString(),
                true
        );

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(documentRepository.findAllOrderByUpdatedAtDesc())
                .singleElement()
                .satisfies(document -> {
                    assertThat(document.knowledgeFolderId()).isEqualTo("folder-sync-ocr-new");
                    assertThat(document.fileType()).isEqualTo(FileType.PDF);
                    assertThat(document.status()).isEqualTo(DocumentStatus.OCR_REQUIRED);
                    assertThat(document.chunkCount()).isZero();
                    assertThat(document.indexedAt()).isNull();
                });
    }

    @Test
    void syncKnowledgeFolderReplacesExistingParsedPdfWithOcrRequired() throws Exception {
        Path pdf = tempDir.resolve("replace-me.pdf");
        writeTextPdf(pdf, "Searchable text before replacement");
        ingestionService.ingestKnowledgeFolder("folder-sync-ocr-existing", tempDir.toString(), true);
        KnowledgeDocument parsedDocument = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();

        assertThat(parsedDocument.status()).isEqualTo(DocumentStatus.PARSED);
        assertThat(parsedDocument.indexedAt()).isNotNull();
        assertThat(documentRepository.findChunksByDocumentId(parsedDocument.id())).isNotEmpty();
        assertThat(knowledgeStore.status().indexedChunkCount()).isPositive();

        writeBlankPdf(pdf);
        IngestDocumentsResponse response = ingestionService.syncKnowledgeFolder(
                "folder-sync-ocr-existing",
                tempDir.toString(),
                true
        );

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(documentRepository.findById(parsedDocument.id()))
                .hasValueSatisfying(document -> {
                    assertThat(document.status()).isEqualTo(DocumentStatus.OCR_REQUIRED);
                    assertThat(document.chunkCount()).isZero();
                    assertThat(document.indexedAt()).isNull();
                });
        assertThat(documentRepository.findChunksByDocumentId(parsedDocument.id())).isEmpty();
        assertThat(knowledgeStore.status().indexedChunkCount()).isZero();
    }

    @Test
    void deleteDocumentOnlyDeletesDatabaseRows() throws Exception {
        Path note = tempDir.resolve("delete-me.txt");
        // 文件系统访问可能抛出 IO 异常，调用方需要保留失败上下文。
        Files.writeString(note, "Keep the original file.");

        ingestionService.ingestFolder(tempDir.toString(), true);
        KnowledgeDocument document = documentRepository.findAllOrderByUpdatedAtDesc().getFirst();

        documentRepository.deleteById(document.id());

        assertThat(documentRepository.findById(document.id())).isEmpty();
        assertThat(note).exists();
    }

    @Test
    void storedChunkLookupCapsLargeInClauseAndKeepsInputOrder() {
        long now = System.currentTimeMillis();
        String documentId = "bulk-document";
        KnowledgeDocument document = new KnowledgeDocument(
                documentId,
                tempDir.resolve("bulk.txt").toString(),
                "bulk.txt",
                FileType.TEXT,
                0,
                now,
                "hash",
                DocumentStatus.PARSED,
                now,
                now,
                now,
                501
        );
        List<KnowledgeChunk> chunks = new ArrayList<>();
        List<String> chunkIds = new ArrayList<>();
        for (int index = 0; index < 501; index++) {
            String chunkId = "chunk-" + index;
            chunkIds.add(chunkId);
            chunks.add(new KnowledgeChunk(
                    chunkId,
                    documentId,
                    index,
                    "content " + index,
                    "hash-" + index,
                    null,
                    null,
                    1,
                    now
            ));
        }

        documentRepository.upsertDocument(document);
        documentRepository.replaceChunks(documentId, chunks);

        assertThat(documentRepository.findStoredChunksByIds(chunkIds))
                .hasSize(500)
                .extracting(chunk -> chunk.chunkId())
                .containsExactlyElementsOf(chunkIds.subList(0, 500));
    }

    private Path fixture(String name) throws Exception {
        URL resource = Objects.requireNonNull(getClass().getResource("/fixtures/" + name));
        return Path.of(resource.toURI());
    }

    private void writeBlankPdf(Path path) throws Exception {
        Files.deleteIfExists(path);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(path.toFile());
        }
    }

    private void writeTextPdf(Path path, String text) throws IOException {
        Files.deleteIfExists(path);
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
}
