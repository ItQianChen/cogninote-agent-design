package com.itqianchen.agentdesign.document;

import com.itqianchen.agentdesign.ingestion.DocumentChunk;
import com.itqianchen.agentdesign.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.ingestion.TextChunker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class DocumentIngestionService {

    private final DocumentRepository documentRepository;
    private final DocumentIngestionPersistence ingestionPersistence;
    private final DocumentParserRegistry parserRegistry;
    private final TextChunker textChunker;
    private final DocumentIdentity documentIdentity;

    public DocumentIngestionService(
            DocumentRepository documentRepository,
            DocumentIngestionPersistence ingestionPersistence,
            DocumentParserRegistry parserRegistry,
            TextChunker textChunker,
            DocumentIdentity documentIdentity
    ) {
        this.documentRepository = documentRepository;
        this.ingestionPersistence = ingestionPersistence;
        this.parserRegistry = parserRegistry;
        this.textChunker = textChunker;
        this.documentIdentity = documentIdentity;
    }

    public IngestDocumentsResponse ingestFolder(String folderPath, boolean recursive) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }

        List<Path> files = scanSupportedFiles(folder, recursive);
        IngestAccumulator accumulator = new IngestAccumulator(files.size());
        for (Path file : files) {
            ingestFile(file, accumulator);
        }

        return accumulator.toResponse();
    }

    private List<Path> scanSupportedFiles(Path folder, boolean recursive) {
        int maxDepth = recursive ? Integer.MAX_VALUE : 1;
        try (Stream<Path> stream = Files.walk(folder, maxDepth)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> FileType.fromFileName(path.getFileName().toString()).isPresent())
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to scan folder: " + folder, ex);
        }
    }

    private void ingestFile(Path file, IngestAccumulator accumulator) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        Optional<FileType> optionalFileType = FileType.fromFileName(normalizedFile.getFileName().toString());
        if (optionalFileType.isEmpty()) {
            return;
        }

        FileType fileType = optionalFileType.get();
        long now = System.currentTimeMillis();
        try {
            FileMetadata metadata = readMetadata(normalizedFile);
            String documentId = documentIdentity.idForPath(normalizedFile.toString());
            Optional<KnowledgeDocument> existing = documentRepository.findById(documentId);

            if (existing.isPresent() && isUnchanged(existing.get(), metadata)) {
                accumulator.skippedCount++;
                return;
            }

            ParsedDocument parsedDocument = parserRegistry.parserFor(fileType).parse(normalizedFile);
            List<DocumentChunk> documentChunks = textChunker.chunk(parsedDocument);
            if (documentChunks.isEmpty()) {
                throw new DocumentParseException("Parsed document contains no usable text: " + normalizedFile);
            }

            KnowledgeDocument document = new KnowledgeDocument(
                    documentId,
                    normalizedFile.toString(),
                    normalizedFile.getFileName().toString(),
                    fileType,
                    metadata.fileSize(),
                    metadata.lastModified(),
                    metadata.contentHash(),
                    DocumentStatus.PARSED,
                    null,
                    existing.map(KnowledgeDocument::createdAt).orElse(now),
                    now,
                    documentChunks.size()
            );
            ingestionPersistence.replaceParsedDocument(document, toKnowledgeChunks(documentId, documentChunks, now));
            accumulator.parsedCount++;
        } catch (RuntimeException ex) {
            recordFailure(normalizedFile, fileType, now, ex, accumulator);
        }
    }

    private FileMetadata readMetadata(Path path) {
        try {
            long fileSize = Files.size(path);
            FileTime lastModifiedTime = Files.getLastModifiedTime(path);
            return new FileMetadata(
                    fileSize,
                    lastModifiedTime.toMillis(),
                    documentIdentity.hashFile(path)
            );
        } catch (IOException ex) {
            throw new DocumentParseException("Failed to read file metadata: " + path, ex);
        }
    }

    private boolean isUnchanged(KnowledgeDocument existing, FileMetadata metadata) {
        return existing.fileSize() == metadata.fileSize()
                && existing.lastModified() == metadata.lastModified()
                && existing.contentHash().equals(metadata.contentHash())
                && existing.status() == DocumentStatus.PARSED;
    }

    private List<KnowledgeChunk> toKnowledgeChunks(String documentId, List<DocumentChunk> documentChunks, long now) {
        return documentChunks.stream()
                .map(chunk -> new KnowledgeChunk(
                        documentIdentity.idForPath(documentId + ":" + chunk.chunkIndex()),
                        documentId,
                        chunk.chunkIndex(),
                        chunk.content(),
                        documentIdentity.hashText(chunk.content()),
                        chunk.pageNumber(),
                        chunk.heading(),
                        chunk.tokenCount(),
                        now
                ))
                .toList();
    }

    private void recordFailure(
            Path normalizedFile,
            FileType fileType,
            long now,
            RuntimeException ex,
            IngestAccumulator accumulator
    ) {
        String documentId = documentIdentity.idForPath(normalizedFile.toString());
        long fileSize = safeFileSize(normalizedFile);
        long lastModified = safeLastModified(normalizedFile);
        String contentHash = safeContentHash(normalizedFile);

        ingestionPersistence.replaceFailedDocument(new KnowledgeDocument(
                documentId,
                normalizedFile.toString(),
                normalizedFile.getFileName().toString(),
                fileType,
                fileSize,
                lastModified,
                contentHash,
                DocumentStatus.FAILED,
                null,
                documentRepository.findById(documentId).map(KnowledgeDocument::createdAt).orElse(now),
                now,
                0
        ));
        accumulator.failedCount++;
        accumulator.failures.add(new IngestFailureResponse(normalizedFile.toString(), ex.getMessage()));
    }

    private long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    private long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    private String safeContentHash(Path path) {
        try {
            return documentIdentity.hashFile(path);
        } catch (IOException ex) {
            return "";
        }
    }

    private record FileMetadata(long fileSize, long lastModified, String contentHash) {
    }

    private static class IngestAccumulator {
        private final int scannedCount;
        private final List<IngestFailureResponse> failures = new ArrayList<>();
        private int parsedCount;
        private int skippedCount;
        private int failedCount;

        private IngestAccumulator(int scannedCount) {
            this.scannedCount = scannedCount;
        }

        private IngestDocumentsResponse toResponse() {
            return new IngestDocumentsResponse(scannedCount, parsedCount, skippedCount, failedCount, List.copyOf(failures));
        }
    }
}
