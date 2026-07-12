package com.itqianchen.agentdesign.repository.document;

import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpoint;
import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpointPage;
import com.itqianchen.agentdesign.mapper.document.DocumentOcrCheckpointMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** PDF OCR 检查点仓储。 */
@Repository
public class DocumentOcrCheckpointRepository {

    private final DocumentOcrCheckpointMapper mapper;

    public DocumentOcrCheckpointRepository(DocumentOcrCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    public Optional<DocumentOcrCheckpoint> findByDocumentId(String documentId) {
        return mapper.findByDocumentId(documentId).stream().findFirst();
    }

    public List<DocumentOcrCheckpointPage> findPagesByDocumentId(String documentId) {
        return mapper.findPagesByDocumentId(documentId);
    }

    public void save(DocumentOcrCheckpoint checkpoint) {
        mapper.upsertCheckpoint(checkpoint);
    }

    public void savePage(DocumentOcrCheckpointPage page) {
        mapper.upsertPage(page);
    }

    public void deleteByDocumentId(String documentId) {
        // 显式删除子表，避免测试或用户环境关闭 SQLite 外键时留下孤儿页面。
        mapper.deletePagesByDocumentId(documentId);
        mapper.deleteByDocumentId(documentId);
    }

    public boolean exists(String documentId, String sourceContentHash) {
        return mapper.countByDocumentIdAndSourceContentHash(documentId, sourceContentHash) > 0;
    }
}
