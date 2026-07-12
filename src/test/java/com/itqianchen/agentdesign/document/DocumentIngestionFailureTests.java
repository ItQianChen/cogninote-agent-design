package com.itqianchen.agentdesign.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.interfaces.ingestion.DocumentParser;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.support.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.domain.support.ingestion.TextChunker;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentChunk;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentParseRequest;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.document.DocumentIngestionPersistence;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import com.itqianchen.agentdesign.service.document.DocumentOcrCheckpointService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentIngestionFailureTests {

    @TempDir
    private Path tempDir;

    @Test
    void failedDocumentPersistenceFailureDoesNotAbortBatch() throws Exception {
        Path brokenDocument = tempDir.resolve("broken.txt");
        // 文件系统访问可能抛出 IO 异常，调用方需要保留失败上下文。
        Files.writeString(brokenDocument, "This parser will fail.");

        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);

        when(documentRepository.findById(anyString())).thenReturn(Optional.empty());
        when(parserRegistry.parserFor(FileType.TEXT)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenThrow(new DocumentParseException("parse failed"));
        doThrow(new IllegalStateException("sqlite unavailable"))
                .when(ingestionPersistence)
                .replaceFailedDocument(any(KnowledgeDocument.class));

        DocumentIngestionService ingestionService = new DocumentIngestionService(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                mock(TextChunker.class),
                new DocumentIdentity(),
                knowledgeStore,
                new DocumentFailureCodec(new ObjectMapper()),
                mock(DocumentOcrCheckpointService.class)
        );

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        assertThat(response.scannedCount()).isEqualTo(1);
        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures())
                .singleElement()
                .satisfies(failure -> {
                    assertThat(failure.stage()).isEqualTo("PARSE");
                    assertThat(failure.code()).isEqualTo("DOCUMENT_CORRUPTED");
                    assertThat(failure.message()).contains("文档格式损坏");
                    assertThat(failure.detail()).isEqualTo("parse failed");
                });
        verify(knowledgeStore).deleteByDocumentId(anyString());
    }

    @Test
    void syncParseFailurePreservesExistingParsedDocumentAndCountsAttemptFailure() throws Exception {
        Path documentPath = tempDir.resolve("preserve.txt");
        Files.writeString(documentPath, "changed content");
        DocumentIdentity documentIdentity = new DocumentIdentity();
        String documentId = documentIdentity.idForPath(documentPath.toAbsolutePath().normalize().toString());
        KnowledgeDocument existing = parsedDocument(documentId, documentPath);

        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(existing));
        when(parserRegistry.parserFor(FileType.TEXT)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenThrow(new DocumentParseException("temporary parse failure"));

        DocumentIngestionService ingestionService = service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                mock(TextChunker.class),
                documentIdentity,
                knowledgeStore
        );

        IngestDocumentsResponse response = ingestionService.syncKnowledgeFolder(
                "folder-preserve",
                tempDir.toString(),
                true
        );

        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.stage()).isEqualTo("PARSE");
            assertThat(failure.code()).isEqualTo("DOCUMENT_CORRUPTED");
        });
        verify(documentRepository).updateLastFailure(
                eq(documentId),
                argThat(failure -> "PARSE".equals(failure.stage())),
                nullable(String.class),
                anyLong()
        );
        verify(ingestionPersistence, never()).replaceFailedDocument(any());
        verify(knowledgeStore, never()).deleteByDocumentId(documentId);
    }

    @Test
    void indexFailurePreservesParsedDocumentAndRecordsIndexDiagnosis() throws Exception {
        Path documentPath = tempDir.resolve("index-warning.txt");
        Files.writeString(documentPath, "changed content");
        DocumentIdentity documentIdentity = new DocumentIdentity();
        String documentId = documentIdentity.idForPath(documentPath.toAbsolutePath().normalize().toString());
        KnowledgeDocument existing = parsedDocument(documentId, documentPath);

        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        TextChunker textChunker = mock(TextChunker.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(existing));
        when(parserRegistry.parserFor(FileType.TEXT)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenReturn(new ParsedDocument(
                FileType.TEXT,
                List.of(new ParsedSection("updated text", null, null))
        ));
        when(textChunker.chunk(any(ParsedDocument.class))).thenReturn(
                List.of(new DocumentChunk(0, "updated text", null, null, 2))
        );
        doThrow(new IllegalStateException("lucene unavailable"))
                .when(knowledgeStore)
                .indexDocument(any());

        DocumentIngestionService ingestionService = service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                textChunker,
                documentIdentity,
                knowledgeStore
        );

        IngestDocumentsResponse response = ingestionService.syncKnowledgeFolder(
                "folder-index",
                tempDir.toString(),
                true
        );

        assertThat(response.failedCount()).isEqualTo(1);
        assertThat(response.failures()).singleElement().satisfies(failure -> {
            assertThat(failure.stage()).isEqualTo("INDEX");
            assertThat(failure.code()).isEqualTo("INDEX_WRITE_FAILED");
        });
        verify(documentRepository).clearIndexed(documentId);
        verify(documentRepository).updateLastFailure(
                eq(documentId),
                argThat(failure -> "INDEX".equals(failure.stage())),
                nullable(String.class),
                anyLong()
        );
        verify(ingestionPersistence, never()).replaceFailedDocument(any());
    }

    @Test
    void directImportFailureReplacesDocumentWithFailedRecord() throws Exception {
        Path documentPath = tempDir.resolve("replace.txt");
        Files.writeString(documentPath, "broken content");
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        when(documentRepository.findById(anyString())).thenReturn(Optional.empty());
        when(parserRegistry.parserFor(FileType.TEXT)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenThrow(new DocumentParseException("parse failed"));

        DocumentIngestionService ingestionService = service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                mock(TextChunker.class),
                new DocumentIdentity(),
                knowledgeStore
        );

        IngestDocumentsResponse response = ingestionService.ingestFolder(tempDir.toString(), true);

        ArgumentCaptor<KnowledgeDocument> documentCaptor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(ingestionPersistence).replaceFailedDocument(documentCaptor.capture());
        assertThat(documentCaptor.getValue().status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(documentCaptor.getValue().lastFailureStage()).isEqualTo("PARSE");
        assertThat(response.failedCount()).isEqualTo(1);
        verify(knowledgeStore).deleteByDocumentId(documentCaptor.getValue().id());
    }

    @Test
    void syncDoesNotSkipUnchangedPdfWithPendingOcrCheckpoint() throws Exception {
        Path documentPath = tempDir.resolve("resume.pdf");
        Files.writeString(documentPath, "fake pdf bytes");
        DocumentIdentity documentIdentity = new DocumentIdentity();
        String normalizedPath = documentPath.toAbsolutePath().normalize().toString();
        String documentId = documentIdentity.idForPath(normalizedPath);
        String contentHash = documentIdentity.hashFile(documentPath);
        long now = System.currentTimeMillis();
        KnowledgeDocument existing = new KnowledgeDocument(
                documentId,
                "folder-resume",
                normalizedPath,
                documentPath.getFileName().toString(),
                FileType.PDF,
                Files.size(documentPath),
                Files.getLastModifiedTime(documentPath).toMillis(),
                contentHash,
                DocumentStatus.PARSED,
                now,
                now,
                now,
                1
        );
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        TextChunker textChunker = mock(TextChunker.class);
        KnowledgeStore knowledgeStore = mock(KnowledgeStore.class);
        DocumentOcrCheckpointService checkpointService = mock(DocumentOcrCheckpointService.class);
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(existing));
        when(checkpointService.exists(documentId, contentHash)).thenReturn(true);
        when(parserRegistry.parserFor(FileType.PDF)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenReturn(new ParsedDocument(
                FileType.PDF,
                List.of(new ParsedSection("resumed page", null, 1))
        ));
        when(textChunker.chunk(any(ParsedDocument.class))).thenReturn(
                List.of(new DocumentChunk(0, "resumed page", 1, null, 2))
        );

        IngestDocumentsResponse response = service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                textChunker,
                documentIdentity,
                knowledgeStore,
                checkpointService
        ).syncKnowledgeFolder("folder-resume", tempDir.toString(), true);

        assertThat(response.parsedCount()).isEqualTo(1);
        assertThat(response.skippedCount()).isZero();
        verify(parser).parse(any(DocumentParseRequest.class));
    }

    @Test
    void reparseClearsPdfCheckpointBeforeParsing() throws Exception {
        Path documentPath = tempDir.resolve("restart.pdf");
        Files.writeString(documentPath, "fake pdf bytes");
        DocumentIdentity documentIdentity = new DocumentIdentity();
        String documentId = documentIdentity.idForPath(documentPath.toAbsolutePath().normalize().toString());
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        DocumentIngestionPersistence ingestionPersistence = mock(DocumentIngestionPersistence.class);
        DocumentParserRegistry parserRegistry = mock(DocumentParserRegistry.class);
        DocumentParser parser = mock(DocumentParser.class);
        TextChunker textChunker = mock(TextChunker.class);
        DocumentOcrCheckpointService checkpointService = mock(DocumentOcrCheckpointService.class);
        when(documentRepository.findById(documentId)).thenReturn(Optional.empty());
        when(parserRegistry.parserFor(FileType.PDF)).thenReturn(parser);
        when(parser.parse(any(DocumentParseRequest.class))).thenReturn(new ParsedDocument(
                FileType.PDF,
                List.of(new ParsedSection("fresh page", null, 1))
        ));
        when(textChunker.chunk(any(ParsedDocument.class))).thenReturn(
                List.of(new DocumentChunk(0, "fresh page", 1, null, 2))
        );

        service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                textChunker,
                documentIdentity,
                mock(KnowledgeStore.class),
                checkpointService
        ).reparseKnowledgeFolder("folder-restart", tempDir.toString(), true);

        verify(checkpointService).clear(documentId);
        verify(parser).parse(any(DocumentParseRequest.class));
    }

    private static DocumentIngestionService service(
            DocumentRepository documentRepository,
            DocumentIngestionPersistence ingestionPersistence,
            DocumentParserRegistry parserRegistry,
            TextChunker textChunker,
            DocumentIdentity documentIdentity,
            KnowledgeStore knowledgeStore
    ) {
        return service(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                textChunker,
                documentIdentity,
                knowledgeStore,
                mock(DocumentOcrCheckpointService.class)
        );
    }

    private static DocumentIngestionService service(
            DocumentRepository documentRepository,
            DocumentIngestionPersistence ingestionPersistence,
            DocumentParserRegistry parserRegistry,
            TextChunker textChunker,
            DocumentIdentity documentIdentity,
            KnowledgeStore knowledgeStore,
            DocumentOcrCheckpointService checkpointService
    ) {
        return new DocumentIngestionService(
                documentRepository,
                ingestionPersistence,
                parserRegistry,
                textChunker,
                documentIdentity,
                knowledgeStore,
                new DocumentFailureCodec(new ObjectMapper()),
                checkpointService
        );
    }

    private static KnowledgeDocument parsedDocument(String documentId, Path path) {
        long now = System.currentTimeMillis();
        return new KnowledgeDocument(
                documentId,
                "folder-existing",
                path.toAbsolutePath().normalize().toString(),
                path.getFileName().toString(),
                FileType.TEXT,
                0,
                0,
                "old-hash",
                DocumentStatus.PARSED,
                now,
                now,
                now,
                1
        );
    }
}
