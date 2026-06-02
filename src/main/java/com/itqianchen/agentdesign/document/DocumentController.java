package com.itqianchen.agentdesign.document;

import com.itqianchen.agentdesign.common.ResourceNotFoundException;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentIngestionService ingestionService;
    private final DocumentRepository documentRepository;

    public DocumentController(DocumentIngestionService ingestionService, DocumentRepository documentRepository) {
        this.ingestionService = ingestionService;
        this.documentRepository = documentRepository;
    }

    @GetMapping
    public List<DocumentSummaryResponse> listDocuments() {
        return documentRepository.findAllOrderByUpdatedAtDesc()
                .stream()
                .map(DocumentSummaryResponse::from)
                .toList();
    }

    @PostMapping("/ingest")
    public IngestDocumentsResponse ingest(@Valid @RequestBody IngestDocumentsRequest request) {
        return ingestionService.ingestFolder(request.folderPath(), request.recursiveOrDefault());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteDocument(@PathVariable String id) {
        if (!documentRepository.deleteById(id)) {
            throw new ResourceNotFoundException("Document not found: " + id);
        }
        return ResponseEntity.noContent().build();
    }
}
