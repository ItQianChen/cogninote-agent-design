package com.itqianchen.agentdesign.mapper.document;

import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpoint;
import com.itqianchen.agentdesign.domain.entity.document.DocumentOcrCheckpointPage;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** PDF OCR 检查点的 MyBatis 边界。 */
public interface DocumentOcrCheckpointMapper {

    List<DocumentOcrCheckpoint> findByDocumentId(@Param("documentId") String documentId);

    List<DocumentOcrCheckpointPage> findPagesByDocumentId(@Param("documentId") String documentId);

    void upsertCheckpoint(DocumentOcrCheckpoint checkpoint);

    void upsertPage(DocumentOcrCheckpointPage page);

    int deletePagesByDocumentId(@Param("documentId") String documentId);

    int deleteByDocumentId(@Param("documentId") String documentId);

    int countByDocumentIdAndSourceContentHash(
            @Param("documentId") String documentId,
            @Param("sourceContentHash") String sourceContentHash
    );
}
