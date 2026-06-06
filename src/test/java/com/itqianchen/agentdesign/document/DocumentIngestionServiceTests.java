package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.document.FileType;
import com.itqianchen.agentdesign.domain.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.support.TestDatabaseCleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private TestDatabaseCleaner databaseCleaner;

    @TempDir
    private Path tempDir;

    @BeforeEach
    void clearDatabase() {
        databaseCleaner.clearDocuments();
    }

    @Test
    void ingestFolderParsesMarkdownAndSkipsUnchangedFiles() throws Exception {
        Path note = tempDir.resolve("note.md");
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
    void deleteDocumentOnlyDeletesDatabaseRows() throws Exception {
        Path note = tempDir.resolve("delete-me.txt");
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
}
