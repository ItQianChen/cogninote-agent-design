package com.itqianchen.agentdesign.service.knowledge;

import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.domain.search.KnowledgeStore;
import com.itqianchen.agentdesign.dto.document.DocumentSummaryResponse;
import com.itqianchen.agentdesign.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.dto.knowledge.KnowledgeFolderResponse;
import com.itqianchen.agentdesign.dto.knowledge.KnowledgeFolderRebuildResponse;
import com.itqianchen.agentdesign.dto.knowledge.KnowledgeFoldersResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRepository;
import com.itqianchen.agentdesign.service.document.DocumentIngestionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KnowledgeFolderService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFolderService.class);

    private final KnowledgeFolderRepository knowledgeFolderRepository;
    private final DocumentRepository documentRepository;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeStore knowledgeStore;
    private final DocumentIdentity documentIdentity;

    public KnowledgeFolderService(
            KnowledgeFolderRepository knowledgeFolderRepository,
            DocumentRepository documentRepository,
            DocumentIngestionService ingestionService,
            KnowledgeStore knowledgeStore,
            DocumentIdentity documentIdentity
    ) {
        this.knowledgeFolderRepository = knowledgeFolderRepository;
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
        this.knowledgeStore = knowledgeStore;
        this.documentIdentity = documentIdentity;
    }

    @Transactional(readOnly = true)
    public KnowledgeFoldersResponse listFolders() {
        List<KnowledgeFolderResponse> folders = knowledgeFolderRepository.findAllSummaries().stream()
                .map(summary -> KnowledgeFolderResponse.from(
                        summary,
                        documentRepository.findByKnowledgeFolderIdOrderByUpdatedAtDesc(summary.folder().id()).stream()
                                .map(DocumentSummaryResponse::from)
                                .toList()
                ))
                .toList();
        List<DocumentSummaryResponse> unassignedDocuments = documentRepository.findUnassignedOrderByUpdatedAtDesc().stream()
                .map(DocumentSummaryResponse::from)
                .toList();
        return new KnowledgeFoldersResponse(folders, unassignedDocuments);
    }

    @Transactional
    public IngestDocumentsResponse importFolder(String folderPath, boolean recursive) {
        KnowledgeFolder folder = upsertFolder(folderPath, recursive, true);
        IngestDocumentsResponse response = ingestionService.ingestKnowledgeFolder(
                folder.id(),
                folder.folderPath(),
                folder.recursive()
        );
        long now = System.currentTimeMillis();
        knowledgeFolderRepository.markIngested(folder.id(), now);
        markFolderIndexedIfAllParsedDocumentsIndexed(folder.id(), now);
        log.info("knowledge_folder_imported folderId={} folderPath={} scanned={} parsed={} skipped={} failed={}",
                folder.id(),
                folder.folderPath(),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount()
        );
        return response;
    }

    private void markFolderIndexedIfAllParsedDocumentsIndexed(String folderId, long indexedAt) {
        List<KnowledgeDocument> parsedDocuments = documentRepository.findByKnowledgeFolderIdOrderByUpdatedAtDesc(folderId)
                .stream()
                .filter(document -> document.status() == DocumentStatus.PARSED)
                .toList();
        if (parsedDocuments.isEmpty()) {
            return;
        }
        boolean allIndexed = parsedDocuments.stream()
                .allMatch(document -> document.indexedAt() != null);
        if (allIndexed) {
            /*
             * 导入流程会尝试增量写 Lucene，但单文档索引失败会把 indexed_at 清空。
             * 目录级 last_indexed_at 只能在目录内 PARSED 文档全部完成索引后更新，避免 UI 显示“已索引”
             * 而搜索实际缺失部分文档。
             */
            knowledgeFolderRepository.markIndexed(folderId, indexedAt);
        }
    }

    @Transactional
    public KnowledgeFolderRebuildResponse rebuildFolder(String id) {
        KnowledgeFolder folder = requireFolder(id);
        if (!folder.enabled()) {
            throw new DocumentParseException("Knowledge folder is disabled: " + folder.displayName());
        }

        Set<String> currentDocumentIds = ingestionService.scanDocumentIds(
                folder.folderPath(),
                folder.recursive()
        );
        IngestDocumentsResponse ingestResponse = ingestionService.rebuildKnowledgeFolder(
                folder.id(),
                folder.folderPath(),
                folder.recursive()
        );
        deleteMissingLocalDocuments(folder.id(), currentDocumentIds);
        RebuildIndexResponse rebuildResponse = knowledgeStore.rebuildByDocumentIds(
                documentRepository.findParsedDocumentsForIndexingByKnowledgeFolderId(folder.id())
        );
        long now = System.currentTimeMillis();
        knowledgeFolderRepository.markIngested(folder.id(), now);
        knowledgeFolderRepository.markIndexed(folder.id(), now);
        log.info("knowledge_folder_rebuilt folderId={} folderPath={} scanned={} indexedDocuments={} failedDocuments={}",
                folder.id(),
                folder.folderPath(),
                ingestResponse.scannedCount(),
                rebuildResponse.indexedDocumentCount(),
                rebuildResponse.failedDocumentCount()
        );
        return KnowledgeFolderRebuildResponse.from(ingestResponse, rebuildResponse);
    }

    @Transactional
    public void setEnabled(String id, boolean enabled) {
        KnowledgeFolder folder = requireFolder(id);
        if (folder.enabled() == enabled) {
            return;
        }

        long now = System.currentTimeMillis();
        knowledgeFolderRepository.updateEnabled(id, enabled, now);
        if (enabled) {
            /*
             * 启用目录时重新写入 Lucene。SQLite 中的 chunks 仍是事实来源，
             * 因此不需要重新解析原文件，除非用户显式点击“重建目录”。
             */
            RebuildIndexResponse response = knowledgeStore.rebuildByDocumentIds(
                    documentRepository.findParsedDocumentsForIndexingByKnowledgeFolderId(id)
            );
            knowledgeFolderRepository.markIndexed(id, System.currentTimeMillis());
            log.info("knowledge_folder_enabled folderId={} indexedDocuments={} failedDocuments={}",
                    id,
                    response.indexedDocumentCount(),
                    response.failedDocumentCount()
            );
            return;
        }

        /*
         * 停用目录只清 Lucene，不删 SQLite。这样搜索/RAG 会立即不再命中，
         * 但用户稍后启用时仍能从本地解析结果快速恢复索引。
         */
        deleteFolderIndexEntries(id);
        documentRepository.clearIndexedByKnowledgeFolderId(id);
        log.info("knowledge_folder_disabled folderId={}", id);
    }

    @Transactional
    public void deleteFolder(String id) {
        requireFolder(id);
        deleteFolderIndexEntries(id);
        int deletedDocuments = documentRepository.deleteByKnowledgeFolderId(id);
        if (!knowledgeFolderRepository.deleteById(id)) {
            throw new ResourceNotFoundException("Knowledge folder not found: " + id);
        }
        log.info("knowledge_folder_deleted folderId={} deletedDocuments={}", id, deletedDocuments);
    }

    private KnowledgeFolder upsertFolder(String folderPath, boolean recursive, boolean enabled) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }

        String normalizedPath = folder.toString();
        long now = System.currentTimeMillis();
        KnowledgeFolder folderRecord = knowledgeFolderRepository.findByFolderPath(normalizedPath)
                .map(existing -> new KnowledgeFolder(
                        existing.id(),
                        existing.folderPath(),
                        displayName(folder),
                        recursive,
                        enabled,
                        existing.lastIngestedAt(),
                        existing.lastIndexedAt(),
                        existing.createdAt(),
                        now
                ))
                .orElseGet(() -> new KnowledgeFolder(
                        documentIdentity.idForPath(normalizedPath),
                        normalizedPath,
                        displayName(folder),
                        recursive,
                        enabled,
                        null,
                        null,
                        now,
                        now
                ));
        knowledgeFolderRepository.upsert(folderRecord);
        return knowledgeFolderRepository.findByFolderPath(normalizedPath)
                .orElse(folderRecord);
    }

    private KnowledgeFolder requireFolder(String id) {
        return knowledgeFolderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge folder not found: " + id));
    }

    private void deleteFolderIndexEntries(String knowledgeFolderId) {
        List<String> documentIds = documentRepository.findDocumentIdsByKnowledgeFolderId(knowledgeFolderId);
        for (String documentId : documentIds) {
            try {
                knowledgeStore.deleteByDocumentId(documentId);
            } catch (RuntimeException ex) {
                // Lucene 是可重建索引，单个删除失败记录日志即可；最终全量重建会重新收敛。
                log.warn("knowledge_folder_index_delete_failed folderId={} documentId={}",
                        knowledgeFolderId,
                        documentId,
                        ex
                );
            }
        }
    }

    private void deleteMissingLocalDocuments(String knowledgeFolderId, Set<String> currentDocumentIds) {
        List<String> staleDocumentIds = documentRepository.findDocumentIdsByKnowledgeFolderId(knowledgeFolderId)
                .stream()
                .filter(documentId -> !currentDocumentIds.contains(documentId))
                .toList();
        for (String documentId : staleDocumentIds) {
            /*
             * 用户已经从本地目录移除了文件，SQLite 中的文档记录也应跟随目录重建收敛。
             * 这只删除应用内元数据和 Lucene 条目，不触碰用户文件系统。
             */
            try {
                knowledgeStore.deleteByDocumentId(documentId);
            } catch (RuntimeException ex) {
                log.warn("knowledge_folder_stale_index_delete_failed folderId={} documentId={}",
                        knowledgeFolderId,
                        documentId,
                        ex
                );
            }
            documentRepository.deleteById(documentId);
        }
        if (!staleDocumentIds.isEmpty()) {
            log.info("knowledge_folder_stale_documents_deleted folderId={} deletedDocuments={}",
                    knowledgeFolderId,
                    staleDocumentIds.size()
            );
        }
    }

    private static String displayName(Path folder) {
        Path fileName = folder.getFileName();
        return fileName == null ? folder.toString() : fileName.toString();
    }
}
