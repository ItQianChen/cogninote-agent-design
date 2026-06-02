package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itqianchen.agentdesign.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.ingestion.DocumentParser;
import com.itqianchen.agentdesign.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.ingestion.TextChunker;
import com.itqianchen.agentdesign.search.KnowledgeStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentIngestionFailureTests {

    @TempDir
    private Path tempDir;

    @Test
    void failedDocumentPersistenceFailureDoesNotAbortBatch() throws Exception {
        Path brokenDocument = tempDir.resolve("broken.txt");
        Files.writeString(brokenDocument, "This parser will fail.");

        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);

        when(documentRepository.findById(anyString())).thenReturn(Optional.empty());
        when(parserRegistry.parserFor(FileType.TEXT)).thenReturn(parser);
        when(parser.parse(any(Path.class))).thenThrow(new DocumentParseException("parse failed"));
        doThrow(new IllegalStateException("sqlite unavailable"))
                .when(ingestionPersistence)
                .replaceFailedDocument(any(KnowledgeDocument.class));

        DocumentIngestionService ingestionService = new DocumentIngestionService(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                mock(TextChunker.class),
                new DocumentIdentity(),
                knowledgeStore
        );

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures())
                .singleElement()
                .satisfies(failure -> assertThat(failure.message()).isEqualTo("parse failed"));
        verify(knowledgeStore).deleteByDocumentId(anyString());
    }
}
