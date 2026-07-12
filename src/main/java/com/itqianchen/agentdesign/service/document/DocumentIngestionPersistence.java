package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.entity.document.KnowledgeChunk;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import com.itqianchen.agentdesign.repository.document.DocumentOcrCheckpointRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文档导入的 SQLite 写入边界。
 *
 * <p>文档元数据和 chunk 必须在同一事务里替换，避免导入中断后出现新文档指向旧 chunk
 * 或 FAILED 文档仍残留旧 chunk 的不一致状态。</p>
 */
@Service
public class DocumentIngestionPersistence {

    private final DocumentRepository documentRepository;
    private final DocumentOcrCheckpointRepository checkpointRepository;

    /**
     * 注入文档仓储。
     *
     * @param documentRepository 文档仓储
     */
    public DocumentIngestionPersistence(
            DocumentRepository documentRepository,
            DocumentOcrCheckpointRepository checkpointRepository
    ) {
        this.documentRepository = documentRepository;
        this.checkpointRepository = checkpointRepository;
    }

    /**
     * 用新的解析结果整体替换文档和 chunk。
     *
     * @param document 文档元数据
     * @param chunks 新的完整 chunk 集合
     */
    @Transactional
    public void replaceParsedDocument(KnowledgeDocument document, List<KnowledgeChunk> chunks) {
        documentRepository.upsertDocument(document);
        documentRepository.replaceChunks(document.id(), chunks);
        checkpointRepository.deleteByDocumentId(document.id());
    }

    /**
     * 写入失败文档记录并清空旧 chunk。
     *
     * @param document 失败文档元数据
     */
    @Transactional
    public void replaceFailedDocument(KnowledgeDocument document) {
        documentRepository.upsertDocument(document);
        documentRepository.replaceChunks(document.id(), List.of());
    }
}


