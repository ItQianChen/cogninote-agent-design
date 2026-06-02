package com.itqianchen.agentdesign.document;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentIngestionPersistence {

    private final DocumentRepository documentRepository;

    public DocumentIngestionPersistence(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Transactional
    public void replaceParsedDocument(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        documentRepository.upsertDocument(document);
        documentRepository.replaceChunks(document.id(), chunks);
    }

    @Transactional
    public void replaceFailedDocument(KnowledgeDocument document) {
        documentRepository.upsertDocument(document);
        documentRepository.replaceChunks(document.id(), List.of());
    }
}
