package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.enums.document.DocumentStatus;
import com.itqianchen.agentdesign.domain.enums.document.FileType;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.interfaces.search.KnowledgeStore;
import com.itqianchen.agentdesign.domain.support.ingestion.DocumentParserRegistry;
import com.itqianchen.agentdesign.domain.support.ingestion.TextChunker;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentChunk;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentParseRequest;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedDocument;
import com.itqianchen.agentdesign.domain.vo.ingestion.ScannedDocumentFile;
import com.itqianchen.agentdesign.domain.vo.search.IndexedChunk;
import com.itqianchen.agentdesign.domain.vo.search.IndexedDocument;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 编排本地文档导入、解析、切片、持久化和索引写入。
 *
 * <p>SQLite 是导入结果的事实来源；Lucene 写入失败时只清空 indexedAt，让后续重建任务补索引，
 * 不能回滚已成功解析的 chunks。</p>
 */
@Service
public class DocumentIngestionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    private final DocumentRepository documentRepository;
    private final DocumentIngestionPersistence ingestionPersistence;
    private final DocumentParserRegistry parserRegistry;
    private final TextChunker textChunker;
    private final DocumentIdentity documentIdentity;
    private final KnowledgeStore knowledgeStore;
    private final DocumentFailureCodec failureCodec;
    private final DocumentOcrCheckpointService ocrCheckpointService;

    /**
     * 注入文档导入流程依赖。
     *
     * @param documentRepository 文档仓储
     * @param ingestionPersistence 导入事务写入边界
     * @param parserRegistry 文档解析器注册表
     * @param textChunker 文本切块器
     * @param documentIdentity 稳定 ID 和哈希生成器
     * @param knowledgeStore 检索索引边界
     * @param failureCodec 失败诊断编解码器
     * @param ocrCheckpointService PDF OCR 页级检查点服务
     */
    public DocumentIngestionService(
            DocumentRepository documentRepository,
            DocumentIngestionPersistence ingestionPersistence,
            DocumentParserRegistry parserRegistry,
            TextChunker textChunker,
            DocumentIdentity documentIdentity,
            KnowledgeStore knowledgeStore,
            DocumentFailureCodec failureCodec,
            DocumentOcrCheckpointService ocrCheckpointService
    ) {
        this.documentRepository = documentRepository;
        this.ingestionPersistence = ingestionPersistence;
        this.parserRegistry = parserRegistry;
        this.textChunker = textChunker;
        this.documentIdentity = documentIdentity;
        this.knowledgeStore = knowledgeStore;
        this.failureCodec = failureCodec;
        this.ocrCheckpointService = ocrCheckpointService;
    }

    /**
     * 导入普通本地目录。
     *
     * @param folderPath 目录路径
     * @param recursive 是否递归扫描子目录
     * @return 本次扫描、解析、跳过和失败统计
     */
    public IngestDocumentsResponse ingestFolder(String folderPath, boolean recursive) {
        return ingestFolder(folderPath, recursive, null);
    }

    /**
     * 导入知识库目录并把文档归属到指定文件夹。
     *
     * <p>这是用户主动导入动作，解析失败会写入失败状态记录，便于前端显示失败文件。</p>
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 导入统计
     */
    public IngestDocumentsResponse ingestKnowledgeFolder(String knowledgeFolderId, String folderPath, boolean recursive) {
        if (knowledgeFolderId == null || knowledgeFolderId.isBlank()) {
            throw new DocumentParseException("Knowledge folder id is required");
        }
        return ingestFolder(folderPath, recursive, knowledgeFolderId, FailedDocumentPolicy.REPLACE_WITH_FAILED_RECORD);
    }

    /**
     * 同步知识库目录的文件差异。
     *
     * <p>同步会跳过未变化文件，只解析新增或修改文件，并为索引缺失的旧文档补写 Lucene。
     * 单个文件临时不可读时保留旧解析结果；PDF 无文本层属于稳定内容状态，会覆盖为 OCR_REQUIRED。</p>
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 同步扫描统计
     */
    public IngestDocumentsResponse syncKnowledgeFolder(String knowledgeFolderId, String folderPath, boolean recursive) {
        if (knowledgeFolderId == null || knowledgeFolderId.isBlank()) {
            throw new DocumentParseException("Knowledge folder id is required");
        }
        return ingestFolder(folderPath, recursive, knowledgeFolderId, FailedDocumentPolicy.PRESERVE_EXISTING_RECORD);
    }

    /**
     * 重建知识库目录。
     *
     * <p>重建属于维护动作，单个文件临时不可读时保留旧解析结果；PDF 无文本层属于稳定内容状态，
     * 会覆盖为 OCR_REQUIRED，避免旧文本继续被检索。</p>
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 重建导入统计
     */
    public IngestDocumentsResponse rebuildKnowledgeFolder(String knowledgeFolderId, String folderPath, boolean recursive) {
        if (knowledgeFolderId == null || knowledgeFolderId.isBlank()) {
            throw new DocumentParseException("Knowledge folder id is required");
        }
        /*
         * 目录重建是维护动作，普通解析失败不能覆盖旧的 PARSED/chunks。
         * OCR_REQUIRED 会在失败处理里强制落库，因为当前文件已经无法提供文本层。
         */
        return ingestFolder(folderPath, recursive, knowledgeFolderId, FailedDocumentPolicy.PRESERVE_EXISTING_RECORD);
    }

    /**
     * 强制重新解析知识库目录。
     *
     * <p>REPARSE 会忽略未变化文件跳过逻辑，用于 OCR 配置开启后重新处理旧的 OCR_REQUIRED PDF。
     * 单个文件普通解析失败时仍保留旧的 PARSED 结果，避免外部 OCR 或临时 I/O 波动破坏已有知识库。</p>
     *
     * @param knowledgeFolderId 知识库目录 ID
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 重新解析统计
     */
    public IngestDocumentsResponse reparseKnowledgeFolder(String knowledgeFolderId, String folderPath, boolean recursive) {
        if (knowledgeFolderId == null || knowledgeFolderId.isBlank()) {
            throw new DocumentParseException("Knowledge folder id is required");
        }
        return ingestFolder(folderPath, recursive, knowledgeFolderId,
                FailedDocumentPolicy.PRESERVE_EXISTING_RECORD, true);
    }

    /**
     * 使用默认失败策略导入目录。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @param knowledgeFolderId 可选知识库目录 ID
     * @return 导入统计
     */
    private IngestDocumentsResponse ingestFolder(String folderPath, boolean recursive, String knowledgeFolderId) {
        return ingestFolder(folderPath, recursive, knowledgeFolderId, FailedDocumentPolicy.REPLACE_WITH_FAILED_RECORD);
    }

    /**
     * 按指定失败策略导入目录。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @param knowledgeFolderId 可选知识库目录 ID
     * @param failedDocumentPolicy 解析失败时的记录策略
     * @return 导入统计
     */
    private IngestDocumentsResponse ingestFolder(
            String folderPath,
            boolean recursive,
            String knowledgeFolderId,
            FailedDocumentPolicy failedDocumentPolicy
    ) {
        return ingestFolder(folderPath, recursive, knowledgeFolderId, failedDocumentPolicy, false);
    }

    /**
     * 按指定失败策略和解析模式导入目录。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @param knowledgeFolderId 可选知识库目录 ID
     * @param failedDocumentPolicy 解析失败时的记录策略
     * @param forceReparse 是否忽略未变化文件跳过逻辑
     * @return 导入统计
     */
    private IngestDocumentsResponse ingestFolder(
            String folderPath,
            boolean recursive,
            String knowledgeFolderId,
            FailedDocumentPolicy failedDocumentPolicy,
            boolean forceReparse
    ) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        // 在入口处统一规范化路径，后续 documentId 和目录扫描都基于同一表示。
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }

        List<Path> files = scanSupportedFiles(folder, recursive);
        IngestAccumulator accumulator = new IngestAccumulator(files.size());
        for (Path file : files) {
            ingestFile(file, accumulator, knowledgeFolderId, failedDocumentPolicy, forceReparse);
        }

        return accumulator.toResponse();
    }

    /**
     * 扫描目录下当前可导入文件对应的文档 ID。
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 当前文件集合对应的文档 ID
     */
    public Set<String> scanDocumentIds(String folderPath, boolean recursive) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        // 删除缺失本地文件时复用同一套扫描规则，避免导入和清理看到不同文件集合。
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }

        Set<String> documentIds = new LinkedHashSet<>();
        for (Path file : scanSupportedFiles(folder, recursive)) {
            documentIds.add(documentIdentity.idForPath(file.toAbsolutePath().normalize().toString()));
        }
        return documentIds;
    }

    /**
     * 扫描目录下当前可导入文件的轻量快照。
     *
     * <p>该方法服务于健康诊断，只读取路径和文件元数据，不解析正文、不写 SQLite 或 Lucene。</p>
     *
     * @param folderPath 本地目录路径
     * @param recursive 是否递归扫描
     * @return 当前受支持文件快照
     */
    public List<ScannedDocumentFile> scanDocumentFiles(String folderPath, boolean recursive) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }
        return scanSupportedFiles(folder, recursive).stream()
                .map(path -> path.toAbsolutePath().normalize())
                .map(file -> new ScannedDocumentFile(
                        documentIdentity.idForPath(file.toString()),
                        file.toString(),
                        file.getFileName().toString(),
                        lastModifiedOrZero(file)
                ))
                .toList();
    }

    /**
     * 扫描目录中的受支持文件。
     *
     * @param folder 本地目录
     * @param recursive 是否递归扫描
     * @return 按路径排序的文件列表
     */
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

    private static long lastModifiedOrZero(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 导入单个文件。
     *
     * @param file 文件路径
     * @param accumulator 本轮导入统计
     * @param knowledgeFolderId 可选知识库目录 ID
     * @param failedDocumentPolicy 解析失败策略
     */
    private void ingestFile(
            Path file,
            IngestAccumulator accumulator,
            String knowledgeFolderId,
            FailedDocumentPolicy failedDocumentPolicy,
            boolean forceReparse
    ) {
        Path normalizedFile = file.toAbsolutePath().normalize();
        Optional<FileType> optionalFileType = FileType.fromFileName(normalizedFile.getFileName().toString());
        if (optionalFileType.isEmpty()) {
            return;
        }

        FileType fileType = optionalFileType.get();
        long now = System.currentTimeMillis();
        DocumentFailureStage stage = DocumentFailureStage.READ;
        try {
            FileMetadata metadata = readMetadata(normalizedFile);
            String documentId = documentIdentity.idForPath(normalizedFile.toString());
            Optional<KnowledgeDocument> existing = documentRepository.findById(documentId);

            stage = DocumentFailureStage.PERSIST;
            boolean pendingOcrWork = fileType == FileType.PDF
                    && existing.isPresent()
                    && hasPendingOcrWork(existing.get(), metadata);
            stage = DocumentFailureStage.READ;
            if (!forceReparse && existing.isPresent() && isUnchanged(existing.get(), metadata) && !pendingOcrWork) {
                assignKnowledgeFolderIfNeeded(existing.get(), knowledgeFolderId, now);
                if (existing.get().indexedAt() == null) {
                    // SQLite 已有解析结果但索引缺失时，跳过重新解析，只补 Lucene 索引。
                    stage = DocumentFailureStage.INDEX;
                    indexExistingDocument(documentId);
                }
                accumulator.skippedCount++;
                return;
            }

            if (forceReparse && fileType == FileType.PDF) {
                stage = DocumentFailureStage.PERSIST;
                ocrCheckpointService.clear(documentId);
            }

            stage = DocumentFailureStage.PARSE;
            KnowledgeDocument checkpointPlaceholder = checkpointPlaceholder(
                    documentId,
                    knowledgeFolderId,
                    normalizedFile,
                    fileType,
                    metadata,
                    existing,
                    now
            );
            ParsedDocument parsedDocument = parserRegistry.parserFor(fileType).parse(new DocumentParseRequest(
                    normalizedFile,
                    new PersistentDocumentParseCheckpoint(ocrCheckpointService, checkpointPlaceholder)
            ));
            stage = DocumentFailureStage.CHUNK;
            List<DocumentChunk> documentChunks = textChunker.chunk(parsedDocument);
            if (documentChunks.isEmpty()) {
                throw new DocumentParseException("Parsed document contains no usable text: " + normalizedFile);
            }

            KnowledgeDocument document = new KnowledgeDocument(
                    documentId,
                    knowledgeFolderId,
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
            List<KnowledgeChunk> chunks = toKnowledgeChunks(documentId, documentChunks, now);
            stage = DocumentFailureStage.PERSIST;
            ingestionPersistence.replaceParsedDocument(document, chunks);
            stage = DocumentFailureStage.INDEX;
            indexParsedDocument(toIndexedDocument(document, chunks));
            accumulator.parsedCount++;
        } catch (RuntimeException ex) {
            recordFailure(
                    normalizedFile,
                    fileType,
                    knowledgeFolderId,
                    now,
                    stage,
                    ex,
                    accumulator,
                    failedDocumentPolicy
            );
        }
    }

    /**
     * 在内容未变化时补充文档目录归属。
     *
     * @param existing 已有文档
     * @param knowledgeFolderId 新知识库目录 ID
     * @param now 更新时间戳
     */
    private void assignKnowledgeFolderIfNeeded(KnowledgeDocument existing, String knowledgeFolderId, long now) {
        if (knowledgeFolderId == null || knowledgeFolderId.isBlank() || knowledgeFolderId.equals(existing.knowledgeFolderId())) {
            return;
        }

        /*
         * 用户把历史导入过的目录重新作为“知识库文件夹”导入时，文件内容通常没变。
         * 这类文件会走 skip 分支，所以必须在这里补目录归属，否则页面目录下会缺少这些旧文档。
         */
        documentRepository.updateKnowledgeFolderId(existing.id(), knowledgeFolderId, now);
    }

    /**
     * 读取文件元数据和内容哈希。
     *
     * @param path 文件路径
     * @return 文件元数据
     */
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

    /**
     * 判断文件是否相对已有解析结果未变化。
     *
     * @param existing 已有文档记录
     * @param metadata 当前文件元数据
     * @return 是否可跳过重新解析
     */
    private boolean isUnchanged(KnowledgeDocument existing, FileMetadata metadata) {
        return existing.fileSize() == metadata.fileSize()
                && existing.lastModified() == metadata.lastModified()
                && existing.contentHash().equals(metadata.contentHash())
                && existing.status() == DocumentStatus.PARSED;
    }

    private boolean hasPendingOcrWork(KnowledgeDocument existing, FileMetadata metadata) {
        if (ocrCheckpointService.exists(existing.id(), metadata.contentHash())) {
            return true;
        }
        String failureStage = existing.lastFailureStage();
        return DocumentFailureStage.OCR.name().equals(failureStage)
                || DocumentFailureStage.MODEL_CONFIG.name().equals(failureStage)
                || DocumentFailureStage.MODEL_CALL.name().equals(failureStage);
    }

    private KnowledgeDocument checkpointPlaceholder(
            String documentId,
            String knowledgeFolderId,
            Path path,
            FileType fileType,
            FileMetadata metadata,
            Optional<KnowledgeDocument> existing,
            long now
    ) {
        return new KnowledgeDocument(
                documentId,
                knowledgeFolderId,
                path.toString(),
                path.getFileName().toString(),
                fileType,
                metadata.fileSize(),
                metadata.lastModified(),
                metadata.contentHash(),
                DocumentStatus.OCR_REQUIRED,
                null,
                existing.map(KnowledgeDocument::createdAt).orElse(now),
                now,
                0
        );
    }

    /**
     * 将切块结果转换为持久化 chunk。
     *
     * @param documentId 文档 ID
     * @param documentChunks 切块结果
     * @param now 创建时间戳
     * @return 持久化 chunk 列表
     */
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

    /**
     * 记录单文件导入失败。
     *
     * @param normalizedFile 规范化文件路径
     * @param fileType 文件类型
     * @param knowledgeFolderId 可选知识库目录 ID
     * @param now 当前时间戳
     * @param stage 失败发生阶段
     * @param ex 导入异常
     * @param accumulator 本轮导入统计
     * @param failedDocumentPolicy 失败策略
     */
    private void recordFailure(
            Path normalizedFile,
            FileType fileType,
            String knowledgeFolderId,
            long now,
            DocumentFailureStage stage,
            RuntimeException ex,
            IngestAccumulator accumulator,
            FailedDocumentPolicy failedDocumentPolicy
    ) {
        String documentId = documentIdentity.idForPath(normalizedFile.toString());
        DocumentFailureResponse failure = DocumentFailureClassifier.classify(normalizedFile, stage, ex, now);
        DocumentStatus failureStatus = statusForFailure(failure);
        Optional<KnowledgeDocument> existing = safeFindDocument(documentId);
        boolean preserveExisting = shouldPreserveExistingRecord(
                failedDocumentPolicy,
                stage,
                failureStatus,
                existing
        );
        if (!preserveExisting) {
            long fileSize = safeFileSize(normalizedFile);
            long lastModified = safeLastModified(normalizedFile);
            String contentHash = safeContentHash(normalizedFile);

            KnowledgeDocument failedDocument = new KnowledgeDocument(
                    documentId,
                    knowledgeFolderId,
                    normalizedFile.toString(),
                    normalizedFile.getFileName().toString(),
                    fileType,
                    fileSize,
                    lastModified,
                    contentHash,
                    failureStatus,
                    null,
                    existingCreatedAtOrNow(documentId, now),
                    now,
                    0,
                    failure.stage(),
                    failure.code(),
                    failure.message(),
                    failure.detail(),
                    failureCodec.encodeContext(failure),
                    failure.occurredAt()
            );

            try {
                ingestionPersistence.replaceFailedDocument(failedDocument);
            } catch (RuntimeException persistenceEx) {
                // 即使 SQLite 无法写入失败标记，也要把解析失败反馈给调用方。
                // 批量导入不能因为单个失败记录持久化异常而整体中断。
                log.warn("document_failure_record_failed documentId={} fileName={}",
                        documentId,
                        normalizedFile.getFileName(),
                        persistenceEx
                );
            }
            deleteDocumentIndex(documentId);
        } else {
            persistLastFailure(existing.orElseThrow(), failure, now);
            log.warn("document_processing_failed_preserve_existing documentId={} fileName={} stage={} code={} detail={}",
                    documentId,
                    normalizedFile.getFileName(),
                    failure.stage(),
                    failure.code(),
                    failure.detail()
            );
        }
        // failedCount 描述本次处理失败次数；是否保留旧结果只影响文档可用状态，不隐藏操作失败。
        accumulator.failedCount++;
        accumulator.failures.add(failure);
    }

    /**
     * 将解析异常映射为持久化状态。
     *
     * <p>OCR_REQUIRED 是用户可处理的 PDF 文本层缺失诊断；其他异常继续归入通用 FAILED。</p>
     *
     * @param failure 结构化失败诊断
     * @return 文档失败状态
     */
    private static DocumentStatus statusForFailure(DocumentFailureResponse failure) {
        return DocumentFailureCode.OCR_REQUIRED.name().equals(failure.code())
                ? DocumentStatus.OCR_REQUIRED
                : DocumentStatus.FAILED;
    }

    /**
     * 判断失败是否需要替换 SQLite 记录。
     *
     * <p>普通解析失败可能是临时 I/O 或损坏文件，维护动作下保留旧结果；OCR_REQUIRED 表示当前 PDF
     * 缺少文本层，继续保留旧 chunk 会让搜索命中过期内容。</p>
     *
     * @param failedDocumentPolicy 失败记录策略
     * @param failureStatus 已归类的失败状态
     * @return 是否写入失败记录并清理旧 chunk / 索引
     */
    private static boolean shouldPreserveExistingRecord(
            FailedDocumentPolicy failedDocumentPolicy,
            DocumentFailureStage stage,
            DocumentStatus failureStatus,
            Optional<KnowledgeDocument> existing
    ) {
        if (existing.isEmpty() || existing.get().status() != DocumentStatus.PARSED) {
            return false;
        }
        if (stage == DocumentFailureStage.INDEX) {
            return true;
        }
        return failedDocumentPolicy == FailedDocumentPolicy.PRESERVE_EXISTING_RECORD
                && failureStatus != DocumentStatus.OCR_REQUIRED;
    }

    private Optional<KnowledgeDocument> safeFindDocument(String documentId) {
        try {
            return documentRepository.findById(documentId);
        } catch (RuntimeException ex) {
            log.warn("document_failure_existing_lookup_failed documentId={}", documentId, ex);
            return Optional.empty();
        }
    }

    private void persistLastFailure(KnowledgeDocument existing, DocumentFailureResponse failure, long now) {
        try {
            documentRepository.updateLastFailure(
                    existing.id(),
                    failure,
                    failureCodec.encodeContext(failure),
                    now
            );
        } catch (RuntimeException ex) {
            log.warn("document_last_failure_persist_failed documentId={} fileName={}",
                    existing.id(),
                    existing.fileName(),
                    ex
            );
        }
    }

    /**
     * 查询失败记录应沿用的 createdAt。
     *
     * @param documentId 文档 ID
     * @param now 当前时间戳
     * @return 已有 createdAt 或当前时间
     */
    private long existingCreatedAtOrNow(String documentId, long now) {
        try {
            return documentRepository.findById(documentId)
                    .map(KnowledgeDocument::createdAt)
                    .orElse(now);
        } catch (RuntimeException ex) {
            // 这里已经处于失败处理路径，created_at 查询异常不应升级成批量导入失败。
            log.warn("document_failure_existing_lookup_failed documentId={}", documentId, ex);
            return now;
        }
    }

    /**
     * 为已有解析结果补写 Lucene 索引。
     *
     * @param documentId 文档 ID
     */
    private void indexExistingDocument(String documentId) {
        documentRepository.findParsedDocumentForIndexing(documentId)
                .ifPresent(this::indexParsedDocument);
    }

    /**
     * 将已解析文档写入索引。
     *
     * @param document 文档索引快照
     */
    private void indexParsedDocument(IndexedDocument document) {
        try {
            knowledgeStore.indexDocument(document);
            documentRepository.markIndexed(document.id(), System.currentTimeMillis());
        } catch (RuntimeException ex) {
            // SQLite 是知识库事实来源，Lucene 只是可重建索引。
            // 索引失败时保留已解析 chunks，并通过 indexed_at=NULL 暴露为“待重建”状态。
            documentRepository.clearIndexed(document.id());
            log.warn("document_index_failed documentId={} fileName={}", document.id(), document.fileName(), ex);
            throw ex;
        }
    }

    /**
     * 删除文档的索引记录。
     *
     * @param documentId 文档 ID
     */
    private void deleteDocumentIndex(String documentId) {
        try {
            knowledgeStore.deleteByDocumentId(documentId);
        } catch (RuntimeException ex) {
            log.warn("document_index_delete_failed documentId={}", documentId, ex);
        }
    }

    /**
     * 将文档和 chunk 转换为索引快照。
     *
     * @param document 文档元数据
     * @param chunks 持久化 chunk
     * @return 索引文档
     */
    private IndexedDocument toIndexedDocument(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        return new IndexedDocument(
                document.id(),
                document.sourcePath(),
                document.fileName(),
                document.fileType(),
                chunks.stream()
                        .map(chunk -> new IndexedChunk(
                                chunk.id(),
                                chunk.documentId(),
                                chunk.chunkIndex(),
                                chunk.content(),
                                chunk.contentHash(),
                                chunk.pageNumber(),
                                chunk.heading()
                        ))
                        .toList()
        );
    }

    /**
     * 安全读取文件大小。
     *
     * @param path 文件路径
     * @return 文件大小；失败时返回 0
     */
    private long safeFileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 安全读取文件修改时间。
     *
     * @param path 文件路径
     * @return 修改时间戳；失败时返回 0
     */
    private long safeLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 安全计算文件内容哈希。
     *
     * @param path 文件路径
     * @return 内容哈希；失败时返回空串
     */
    private String safeContentHash(Path path) {
        try {
            return documentIdentity.hashFile(path);
        } catch (IOException ex) {
            return "";
        }
    }

    private record FileMetadata(long fileSize, long lastModified, String contentHash) {
    }

    /**
     * 控制解析失败时是否覆盖已有文档记录。
     *
     * <p>用户主动导入要暴露失败状态；目录同步/重建要保护旧的 PARSED 结果，但 OCR_REQUIRED
     * 这类确定性内容状态仍会替换旧记录。</p>
     */
    private enum FailedDocumentPolicy {
        REPLACE_WITH_FAILED_RECORD,
        PRESERVE_EXISTING_RECORD
    }

    private static class IngestAccumulator {
        private final int scannedCount;
        private final List<DocumentFailureResponse> failures = new ArrayList<>();
        private int parsedCount;
        private int skippedCount;
        private int failedCount;

        /**
         * 创建导入统计累加器。
         *
         * @param scannedCount 本轮扫描到的文件数
         */
        private IngestAccumulator(int scannedCount) {
            this.scannedCount = scannedCount;
        }

        /**
         * 转换为导入响应。
         *
         * @return 导入响应
         */
        private IngestDocumentsResponse toResponse() {
            return new IngestDocumentsResponse(scannedCount, parsedCount, skippedCount, failedCount, List.copyOf(failures));
        }
    }
}


