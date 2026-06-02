package com.itqianchen.agentdesign.document;

import com.itqianchen.agentdesign.search.KnowledgeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentManagementService {

    private static final Logger log = LoggerFactory.getLogger(DocumentManagementService.class);

    private final DocumentRepository documentRepository;
    private final KnowledgeStore knowledgeStore;

    public DocumentManagementService(DocumentRepository documentRepository, KnowledgeStore knowledgeStore) {
        this.documentRepository = documentRepository;
        this.knowledgeStore = knowledgeStore;
    }

    @Transactional
    public boolean deleteDocument(String documentId) {
        boolean deleted = documentRepository.deleteById(documentId);
        if (!deleted) {
            return false;
        }

        try {
            knowledgeStore.deleteByDocumentId(documentId);
        } catch (RuntimeException ex) {
            // Lucene can be rebuilt from SQLite, so do not resurrect a deleted DB row just because
            // index cleanup failed. A rebuild will converge the index.
            log.warn("delete_document_index_failed documentId={}", documentId, ex);
        }
        return true;
    }
}
