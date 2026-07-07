package com.itqianchen.agentdesign.service.knowledge;


import com.itqianchen.agentdesign.domain.vo.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.vo.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.dto.document.DocumentSummaryResponse;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRebuildResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFoldersResponse;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.graph.KnowledgeGraphRepository;
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

/**
 * 知识库目录的导入、同步、重建、启停和删除编排服务。
 *
 * <p>该服务会同时触碰 SQLite、Lucene 和知识图谱缓存；任何目录级状态变化都必须让三者最终收敛。</p>
 *
 * <p>导入、同步和重建包含文件系统扫描、解析和 Lucene 写入，不应包在一个外层数据库事务里。
 * 文档/chunk 替换由 DocumentIngestionPersistence 控制短事务，避免维护任务长时间占用 SQLite 连接。</p>
 */
@Service
public class KnowledgeFolderService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFolderService.class);

    private final KnowledgeFolderRepository knowledgeFolderRepository;
    private final DocumentRepository documentRepository;
    private final DocumentIngestionService ingestionService;
    private final KnowledgeStore knowledgeStore;
    private final DocumentIdentity documentIdentity;
    private final KnowledgeGraphRepository knowledgeGraphRepository;
    private final KnowledgeFolderRunService runService;

    /**
     * 注入知识库目录编排依赖。
     *
     * @param knowledgeFolderRepository 知识库目录仓储
     * @param documentRepository 文档仓储
     * @param ingestionService 文档导入服务
     * @param knowledgeStore 检索索引边界
     * @param documentIdentity 文档 ID 生成器
     * @param knowledgeGraphRepository 图谱仓储
     * @param runService 知识库维护运行记录服务
     */
    public KnowledgeFolderService(
            KnowledgeFolderRepository knowledgeFolderRepository,
            DocumentRepository documentRepository,
            DocumentIngestionService ingestionService,
            KnowledgeStore knowledgeStore,
            DocumentIdentity documentIdentity,
            KnowledgeGraphRepository knowledgeGraphRepository,
            KnowledgeFolderRunService runService
    ) {
        this.knowledgeFolderRepository = knowledgeFolderRepository;
        this.documentRepository = documentRepository;
        this.ingestionService = ingestionService;
        this.knowledgeStore = knowledgeStore;
        this.documentIdentity = documentIdentity;
        this.knowledgeGraphRepository = knowledgeGraphRepository;
        this.runService = runService;
    }

    /**
     * 组装知识库管理页快照。
     *
     * <p>目录统计、目录内文档和未归属文档在服务层一次性收敛，前端不再自行聚合解析状态。</p>
     *
     * @return 知识库管理页响应
     */
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

    /**
     * 导入本地目录为知识库。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 导入统计
     */
    public IngestDocumentsResponse importFolder(String folderPath, boolean recursive) {
        long startedAt = System.currentTimeMillis();
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
        runService.recordImport(folder.id(), response, startedAt);
        return response;
    }

    /**
     * 如果目录内所有已解析文档都完成索引，则更新目录索引时间。
     *
     * @param folderId 知识库目录 ID
     * @param indexedAt 索引时间戳
     */
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

    /**
     * 同步目录文件变更。
     *
     * <p>同步只处理新增、修改、缺失索引和已删除的文件，不做整目录 Lucene 重建；需要修复 Analyzer、
     * Embedding 维度或索引损坏时仍应使用 rebuildFolder。</p>
     *
     * @param id 知识库目录 ID
     * @return 同步扫描统计
     */
    public IngestDocumentsResponse syncFolder(String id) {
        long startedAt = System.currentTimeMillis();
        KnowledgeFolder folder = requireFolder(id);
        if (!folder.enabled()) {
            throw new DocumentParseException("Knowledge folder is disabled: " + folder.displayName());
        }

        Set<String> currentDocumentIds = ingestionService.scanDocumentIds(
                folder.folderPath(),
                folder.recursive()
        );
        IngestDocumentsResponse response = ingestionService.syncKnowledgeFolder(
                folder.id(),
                folder.folderPath(),
                folder.recursive()
        );
        deleteMissingLocalDocuments(folder.id(), currentDocumentIds);
        long now = System.currentTimeMillis();
        knowledgeFolderRepository.markIngested(folder.id(), now);
        markFolderIndexedIfAllParsedDocumentsIndexed(folder.id(), now);
        log.info("knowledge_folder_synced folderId={} folderPath={} scanned={} parsed={} skipped={} failed={}",
                folder.id(),
                folder.folderPath(),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount()
        );
        runService.recordSync(folder.id(), response, startedAt);
        return response;
    }

    /**
     * 强制重新解析目录文件。
     *
     * <p>该动作不只是补索引，会重新读取和解析所有当前支持文件；用于 OCR 配置开启后处理旧的
     * OCR_REQUIRED PDF。不存在于本地目录的旧记录会同步删除。</p>
     *
     * @param id 知识库目录 ID
     * @return 重新解析统计
     */
    public IngestDocumentsResponse reparseFolder(String id) {
        long startedAt = System.currentTimeMillis();
        KnowledgeFolder folder = requireFolder(id);
        if (!folder.enabled()) {
            throw new DocumentParseException("Knowledge folder is disabled: " + folder.displayName());
        }

        Set<String> currentDocumentIds = ingestionService.scanDocumentIds(
                folder.folderPath(),
                folder.recursive()
        );
        IngestDocumentsResponse response = ingestionService.reparseKnowledgeFolder(
                folder.id(),
                folder.folderPath(),
                folder.recursive()
        );
        deleteMissingLocalDocuments(folder.id(), currentDocumentIds);
        long now = System.currentTimeMillis();
        knowledgeFolderRepository.markIngested(folder.id(), now);
        markFolderIndexedIfAllParsedDocumentsIndexed(folder.id(), now);
        log.info("knowledge_folder_reparsed folderId={} folderPath={} scanned={} parsed={} skipped={} failed={} durationMs={}",
                folder.id(),
                folder.folderPath(),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount(),
                now - startedAt
        );
        return response;
    }

    /**
     * 重新扫描目录并重建目录索引。
     *
     * @param id 知识库目录 ID
     * @return 重建响应
     */
    public KnowledgeFolderRebuildResponse rebuildFolder(String id) {
        long startedAt = System.currentTimeMillis();
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
        KnowledgeFolderRebuildResponse response = KnowledgeFolderRebuildResponse.from(ingestResponse, rebuildResponse);
        runService.recordFolderRebuild(folder.id(), response, startedAt);
        return response;
    }

    /**
     * 只补写目录中已解析但尚未进入 Lucene 的文档。
     *
     * <p>该动作不扫描文件系统、不重新解析 PDF，专门用于供应商限流后从 SQLite chunks 恢复缺失索引。</p>
     *
     * @param id 知识库目录 ID
     * @return 补写索引统计
     */
    public RebuildIndexResponse repairFolderIndex(String id) {
        KnowledgeFolder folder = requireFolder(id);
        if (!folder.enabled()) {
            throw new DocumentParseException("Knowledge folder is disabled: " + folder.displayName());
        }

        RebuildIndexResponse response = knowledgeStore.rebuildByDocumentIds(
                documentRepository.findUnindexedParsedDocumentsForIndexingByKnowledgeFolderId(folder.id())
        );
        markFolderIndexedIfAllParsedDocumentsIndexed(folder.id(), System.currentTimeMillis());
        log.info("knowledge_folder_index_repaired folderId={} indexedDocuments={} failedDocuments={}",
                folder.id(),
                response.indexedDocumentCount(),
                response.failedDocumentCount()
        );
        return response;
    }

    /**
     * 切换目录检索可见性。
     *
     * <p>启用时从 SQLite chunks 重建 Lucene，停用时只清索引并保留文档记录。</p>
     *
     * @param id 知识库目录 ID
     * @param enabled 是否启用
     */
    public void setEnabled(String id, boolean enabled) {
        long startedAt = System.currentTimeMillis();
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
            runService.recordEnabled(id, true, response, startedAt);
            return;
        }

        /*
         * 停用目录只清 Lucene，不删 SQLite。这样搜索/RAG 会立即不再命中，
         * 但用户稍后启用时仍能从本地解析结果快速恢复索引。
         */
        deleteFolderIndexEntries(id);
        documentRepository.clearIndexedByKnowledgeFolderId(id);
        log.info("knowledge_folder_disabled folderId={}", id);
        runService.recordEnabled(id, false, null, startedAt);
    }

    /**
     * 删除知识库目录及其派生数据。
     *
     * <p>删除只影响应用内 SQLite、Lucene 和知识图谱数据，不删除用户本地文件系统中的目录或文件。</p>
     *
     * @param id 知识库目录 ID
     */
    public void deleteFolder(String id) {
        requireFolder(id);
        deleteFolderIndexEntries(id);
        knowledgeGraphRepository.deleteByKnowledgeFolderId(id);
        int deletedDocuments = documentRepository.deleteByKnowledgeFolderId(id);
        if (!knowledgeFolderRepository.deleteById(id)) {
            throw new ResourceNotFoundException("Knowledge folder not found: " + id);
        }
        /*
         * 目录删除后不再保留该目录 scope 的维护历史。否则健康页和运行记录查询会继续暴露
         * 已不存在的目录 ID，让用户误以为还有可信状态数据需要处理。
         */
        runService.deleteFolderRuns(id);
        log.info("knowledge_folder_deleted folderId={} deletedDocuments={}", id, deletedDocuments);
    }

    /**
     * 新增或更新知识库目录记录。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @param enabled 是否启用
     * @return 目录记录
     */
    private KnowledgeFolder upsertFolder(String folderPath, boolean recursive, boolean enabled) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        // 先验证本地目录存在，再保存知识库记录，避免产生无法扫描的配置。
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

    /**
     * 读取知识库目录，不存在时抛出 404。
     *
     * @param id 目录 ID
     * @return 目录记录
     */
    private KnowledgeFolder requireFolder(String id) {
        return knowledgeFolderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge folder not found: " + id));
    }

    /**
     * 删除目录下所有文档的索引条目。
     *
     * @param knowledgeFolderId 知识库目录 ID
     */
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

    /**
     * 删除目录重建后已经不存在于本地文件系统的文档记录。
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @param currentDocumentIds 当前扫描得到的文档 ID 集合
     */
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

    /**
     * 从目录路径提取展示名称。
     *
     * @param folder 目录路径
     * @return 展示名称
     */
    private static String displayName(Path folder) {
        Path fileName = folder.getFileName();
        return fileName == null ? folder.toString() : fileName.toString();
    }
}
