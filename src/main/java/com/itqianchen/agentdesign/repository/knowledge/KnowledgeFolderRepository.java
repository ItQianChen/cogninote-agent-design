package com.itqianchen.agentdesign.repository.knowledge;

import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderMapper;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderSummaryRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class KnowledgeFolderRepository {

    private final KnowledgeFolderMapper knowledgeFolderMapper;

    public KnowledgeFolderRepository(KnowledgeFolderMapper knowledgeFolderMapper) {
        this.knowledgeFolderMapper = knowledgeFolderMapper;
    }

    public List<KnowledgeFolderSummary> findAllSummaries() {
        return knowledgeFolderMapper.findAllSummaries().stream()
                .map(KnowledgeFolderRepository::toSummary)
                .toList();
    }

    public Optional<KnowledgeFolder> findById(String id) {
        return knowledgeFolderMapper.findById(id).stream().findFirst();
    }

    public Optional<KnowledgeFolder> findByFolderPath(String folderPath) {
        return knowledgeFolderMapper.findByFolderPath(folderPath).stream().findFirst();
    }

    public void upsert(KnowledgeFolder folder) {
        knowledgeFolderMapper.upsert(folder);
    }

    public void updateEnabled(String id, boolean enabled, long updatedAt) {
        knowledgeFolderMapper.updateEnabled(id, enabled, updatedAt);
    }

    public void markIngested(String id, long timestamp) {
        knowledgeFolderMapper.markIngested(id, timestamp);
    }

    public void markIndexed(String id, long timestamp) {
        knowledgeFolderMapper.markIndexed(id, timestamp);
    }

    public boolean deleteById(String id) {
        return knowledgeFolderMapper.deleteById(id) > 0;
    }

    private static KnowledgeFolderSummary toSummary(KnowledgeFolderSummaryRow row) {
        return new KnowledgeFolderSummary(
                new KnowledgeFolder(
                        row.id(),
                        row.folderPath(),
                        row.displayName(),
                        row.recursive(),
                        row.enabled(),
                        row.lastIngestedAt(),
                        row.lastIndexedAt(),
                        row.createdAt(),
                        row.updatedAt()
                ),
                row.documentCount(),
                row.parsedCount(),
                row.failedCount(),
                row.chunkCount(),
                row.unindexedCount()
        );
    }
}
