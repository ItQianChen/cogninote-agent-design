package com.itqianchen.agentdesign.repository.knowledge;

import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolder;
import com.itqianchen.agentdesign.domain.vo.knowledge.KnowledgeFolderSummary;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderMapper;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderSummaryRow;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 知识库目录仓储。
 *
 * <p>目录统计行会在这里恢复为领域对象，Service 只关心目录、文档数量和索引状态，不关心 SQL 聚合列。</p>
 */
@Repository
public class KnowledgeFolderRepository {

    private final KnowledgeFolderMapper knowledgeFolderMapper;

    /**
     * 注入知识库目录 Mapper。
     *
     * @param knowledgeFolderMapper SQLite 目录访问接口
     */
    public KnowledgeFolderRepository(KnowledgeFolderMapper knowledgeFolderMapper) {
        this.knowledgeFolderMapper = knowledgeFolderMapper;
    }

    /**
     * 查询所有知识库目录摘要。
     *
     * <p>SQL 聚合列会在这里恢复为领域摘要，服务层无需关心统计字段来源。</p>
     *
     * @return 目录摘要列表
     */
    public List<KnowledgeFolderSummary> findAllSummaries() {
        return knowledgeFolderMapper.findAllSummaries().stream()
                .map(KnowledgeFolderRepository::toSummary)
                .toList();
    }

    /**
     * 按 ID 查询知识库目录。
     *
     * @param id 目录 ID
     * @return 目录记录；不存在时为空
     */
    public Optional<KnowledgeFolder> findById(String id) {
        return knowledgeFolderMapper.findById(id).stream().findFirst();
    }

    /**
     * 按本地路径查询知识库目录。
     *
     * @param folderPath 规范化后的本地目录路径
     * @return 目录记录；不存在时为空
     */
    public Optional<KnowledgeFolder> findByFolderPath(String folderPath) {
        return knowledgeFolderMapper.findByFolderPath(folderPath).stream().findFirst();
    }

    /**
     * 新增或更新知识库目录。
     *
     * @param folder 目录领域对象
     */
    public void upsert(KnowledgeFolder folder) {
        knowledgeFolderMapper.upsert(folder);
    }

    /**
     * 更新目录检索可见性。
     *
     * @param id 目录 ID
     * @param enabled 是否启用
     * @param updatedAt 更新时间戳
     */
    public void updateEnabled(String id, boolean enabled, long updatedAt) {
        knowledgeFolderMapper.updateEnabled(id, enabled, updatedAt);
    }

    /**
     * 标记目录最近一次导入时间。
     *
     * @param id 目录 ID
     * @param timestamp 导入完成时间戳
     */
    public void markIngested(String id, long timestamp) {
        knowledgeFolderMapper.markIngested(id, timestamp);
    }

    /**
     * 标记目录最近一次索引时间。
     *
     * @param id 目录 ID
     * @param timestamp 索引完成时间戳
     */
    public void markIndexed(String id, long timestamp) {
        knowledgeFolderMapper.markIndexed(id, timestamp);
    }

    /**
     * 删除知识库目录记录。
     *
     * @param id 目录 ID
     * @return 是否删除了目录记录
     */
    public boolean deleteById(String id) {
        return knowledgeFolderMapper.deleteById(id) > 0;
    }

    /**
     * 将 SQL 聚合行恢复为目录摘要领域对象。
     *
     * @param row 目录和统计聚合行
     * @return 知识库目录摘要
     */
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
