package com.itqianchen.agentdesign.mapper.knowledge;

import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolder;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface KnowledgeFolderMapper {

    List<KnowledgeFolderSummaryRow> findAllSummaries();

    List<KnowledgeFolder> findById(@Param("id") String id);

    List<KnowledgeFolder> findByFolderPath(@Param("folderPath") String folderPath);

    void upsert(KnowledgeFolder folder);

    void updateEnabled(@Param("id") String id, @Param("enabled") boolean enabled, @Param("updatedAt") long updatedAt);

    void markIngested(@Param("id") String id, @Param("timestamp") long timestamp);

    void markIndexed(@Param("id") String id, @Param("timestamp") long timestamp);

    int deleteById(@Param("id") String id);
}
