package com.itqianchen.agentdesign.service.document;

import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpoint;
import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpointPage;
import com.itqianchen.agentdesign.domain.entity.document.KnowledgeDocument;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentCheckpointPersistenceException;
import com.itqianchen.agentdesign.domain.vo.ingestion.ParsedSection;
import com.itqianchen.agentdesign.repository.document.DocumentOcrCheckpointRepository;
import com.itqianchen.agentdesign.repository.document.DocumentRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 管理 PDF OCR 页级检查点及其事务边界。 */
@Service
public class DocumentOcrCheckpointService {

    private final DocumentOcrCheckpointRepository checkpointRepository;
    private final DocumentRepository documentRepository;

    public DocumentOcrCheckpointService(
            DocumentOcrCheckpointRepository checkpointRepository,
            DocumentRepository documentRepository
    ) {
        this.checkpointRepository = checkpointRepository;
        this.documentRepository = documentRepository;
    }

    /**
     * 准备与当前文件和解析签名匹配的检查点。
     *
     * <p>新文档先写入 OCR_REQUIRED 占位记录，使第 1 页失败或进程退出后仍有可恢复的父记录。
     * 签名不匹配时在同一事务内清除旧页面并建立新任务。</p>
     */
    @Transactional
    public List<ParsedSection> prepare(
            KnowledgeDocument placeholder,
            String parserSignature,
            int totalPages
    ) {
        try {
            if (documentRepository.findById(placeholder.id()).isEmpty()) {
                documentRepository.upsertDocument(placeholder);
            }

            var existing = checkpointRepository.findByDocumentId(placeholder.id());
            boolean reusable = existing
                    .filter(checkpoint -> checkpoint.sourceContentHash().equals(placeholder.contentHash()))
                    .filter(checkpoint -> checkpoint.ocrSignature().equals(parserSignature))
                    .filter(checkpoint -> checkpoint.totalPages() == totalPages)
                    .isPresent();
            if (!reusable) {
                checkpointRepository.deleteByDocumentId(placeholder.id());
                long now = System.currentTimeMillis();
                checkpointRepository.save(new DocumentOcrCheckpoint(
                        placeholder.id(),
                        placeholder.contentHash(),
                        parserSignature,
                        totalPages,
                        now,
                        now
                ));
            }

            return checkpointRepository.findPagesByDocumentId(placeholder.id()).stream()
                    .map(page -> new ParsedSection(page.pageText(), null, page.pageNumber()))
                    .toList();
        } catch (RuntimeException ex) {
            throw new DocumentCheckpointPersistenceException(
                    "Failed to prepare PDF OCR checkpoint: " + placeholder.sourcePath(),
                    ex
            );
        }
    }

    /** 单独提交一页，保证后续页面失败时当前结果仍可恢复。 */
    @Transactional
    public void savePage(String documentId, ParsedSection section) {
        if (section.pageNumber() == null) {
            throw new IllegalArgumentException("OCR checkpoint page number is required");
        }
        try {
            long now = System.currentTimeMillis();
            checkpointRepository.savePage(new DocumentOcrCheckpointPage(
                    documentId,
                    section.pageNumber(),
                    section.content() == null ? "" : section.content(),
                    now,
                    now
            ));
        } catch (RuntimeException ex) {
            throw new DocumentCheckpointPersistenceException(
                    "Failed to save PDF OCR checkpoint page " + section.pageNumber(),
                    ex
            );
        }
    }

    @Transactional(readOnly = true)
    public boolean exists(String documentId, String sourceContentHash) {
        return checkpointRepository.exists(documentId, sourceContentHash);
    }

    @Transactional
    public void clear(String documentId) {
        checkpointRepository.deleteByDocumentId(documentId);
    }
}
