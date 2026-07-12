package com.itqianchen.agentdesign.repository.knowledge;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderRunMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 知识库维护运行记录仓储。
 */
@Repository
public class KnowledgeFolderRunRepository {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final KnowledgeFolderRunMapper mapper;

    /**
     * 注入维护运行记录 Mapper。
     *
     * @param mapper SQLite 运行记录访问接口
     */
    public KnowledgeFolderRunRepository(KnowledgeFolderRunMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 保存维护运行记录。
     *
     * @param run 运行记录
     */
    public void insert(KnowledgeFolderRun run) {
        mapper.insertRun(run);
    }

    public Optional<KnowledgeFolderRun> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<KnowledgeFolderRun> findActiveByScopeAndOperation(
            KnowledgeFolderRunScopeType scopeType,
            String scopeId,
            KnowledgeFolderRunOperation operation
    ) {
        return Optional.ofNullable(mapper.findActiveByScopeAndOperation(scopeType.name(), scopeId, operation.name()));
    }

    public List<KnowledgeFolderRun> findActiveRuns() {
        return mapper.findActiveRuns();
    }

    public List<KnowledgeFolderRun> findQueuedRuns() {
        return mapper.findQueuedRuns();
    }

    public List<KnowledgeFolderRun> findQueueRuns() {
        return mapper.findQueueRuns();
    }

    /**
     * 查询指定范围最近的运行记录。
     *
     * @param scopeType 范围类型；为空时不限制
     * @param scopeId 范围 ID；全库范围为空
     * @param limit 最大返回数量
     * @return 运行记录列表
     */
    public List<KnowledgeFolderRun> findRuns(KnowledgeFolderRunScopeType scopeType, String scopeId, Integer limit) {
        int normalizedLimit = normalizeLimit(limit);
        return mapper.findRuns(scopeType == null ? null : scopeType.name(), scopeId, normalizedLimit);
    }

    /**
     * 分页查询维护运行记录。
     *
     * @param scopeType 范围类型；为空时不限制
     * @param scopeId 范围 ID；全库范围为空
     * @param operations 操作类型过滤；为空时不限制
     * @param statuses 状态过滤；为空时不限制
     * @param keyword 模糊关键词，匹配任务 ID、目录名、目录路径、当前项和错误信息
     * @param timeFrom 起始时间戳；为空时不限制
     * @param timeTo 结束时间戳；为空时不限制
     * @param page 页码，从 1 开始
     * @param pageSize 每页数量
     * @return 当前页运行记录
     */
    public List<KnowledgeFolderRun> findRunsPage(
            KnowledgeFolderRunScopeType scopeType,
            String scopeId,
            List<KnowledgeFolderRunOperation> operations,
            List<KnowledgeFolderRunStatus> statuses,
            String keyword,
            Long timeFrom,
            Long timeTo,
            Integer page,
            Integer pageSize
    ) {
        int normalizedPageSize = normalizePageSize(pageSize);
        int offset = normalizeOffset(page, normalizedPageSize);
        return mapper.findRunsPage(
                scopeType == null ? null : scopeType.name(),
                scopeId,
                enumNames(operations),
                enumNames(statuses),
                normalizeKeyword(keyword),
                timeFrom,
                timeTo,
                normalizedPageSize,
                offset
        );
    }

    /**
     * 统计维护运行记录数量。
     *
     * @param scopeType 范围类型；为空时不限制
     * @param scopeId 范围 ID；全库范围为空
     * @return 记录数量
     */
    public long countRuns(
            KnowledgeFolderRunScopeType scopeType,
            String scopeId,
            List<KnowledgeFolderRunOperation> operations,
            List<KnowledgeFolderRunStatus> statuses,
            String keyword,
            Long timeFrom,
            Long timeTo
    ) {
        return mapper.countRuns(
                scopeType == null ? null : scopeType.name(),
                scopeId,
                enumNames(operations),
                enumNames(statuses),
                normalizeKeyword(keyword),
                timeFrom,
                timeTo
        );
    }

    /**
     * 查询每个 scope 最近一次维护记录。
     *
     * @return 最近运行记录列表
     */
    public List<KnowledgeFolderRun> findLatestRunsByScope() {
        return mapper.findLatestRunsByScope();
    }

    public Optional<KnowledgeFolderRun> findLatestRun() {
        return Optional.ofNullable(mapper.findLatestRun());
    }

    public int markStarted(String id, String phase, long progressTotal, String currentItem, long startedAt) {
        return mapper.markStarted(id, phase, progressTotal, currentItem, startedAt);
    }

    public int updateProgress(String id, String phase, long progressCurrent, long progressTotal, String currentItem) {
        return mapper.updateProgress(id, phase, progressCurrent, progressTotal, currentItem, System.currentTimeMillis());
    }

    public int markCancelling(String id) {
        return mapper.markCancelling(id, System.currentTimeMillis());
    }

    public int markCancelled(String id, String message) {
        return mapper.markCancelled(id, message, System.currentTimeMillis());
    }

    public int markCompleted(
            String id,
            KnowledgeFolderRunStatus status,
            int scannedCount,
            int parsedCount,
            int skippedCount,
            int failedCount,
            long indexedDocumentCount,
            long indexedChunkCount,
            long failedDocumentCount,
            String failuresJson,
            long progressCurrent,
            long progressTotal
    ) {
        return mapper.markCompleted(
                id,
                status.name(),
                scannedCount,
                parsedCount,
                skippedCount,
                failedCount,
                indexedDocumentCount,
                indexedChunkCount,
                failedDocumentCount,
                failuresJson,
                progressCurrent,
                progressTotal,
                System.currentTimeMillis()
        );
    }

    public int markFailed(String id, String message) {
        return markFailed(id, message, null, null, null);
    }

    public int markFailed(String id, String message, String errorStage, String errorCode, String errorDetail) {
        return mapper.markFailed(
                id,
                message,
                errorStage,
                errorCode,
                errorDetail,
                System.currentTimeMillis()
        );
    }

    public int cleanupInterruptedRuns() {
        return mapper.cleanupInterruptedRuns(
                System.currentTimeMillis(),
                "应用重启，排队中的维护任务已取消。",
                "应用重启，运行中的维护任务已中断。"
        );
    }

    /**
     * 删除指定范围的运行记录。
     *
     * @param scopeType 范围类型
     * @param scopeId 范围 ID；全库范围为空
     * @return 删除的记录数量
     */
    public int deleteByScope(KnowledgeFolderRunScopeType scopeType, String scopeId) {
        return mapper.deleteByScope(scopeType.name(), scopeId);
    }

    /**
     * 删除一条终态维护历史记录。
     *
     * <p>运行中和排队中的记录是队列状态源，必须通过维护队列接口流转，不能被历史清理入口移除。</p>
     *
     * @param id 运行记录 ID
     * @return 是否删除成功
     */
    public boolean deleteTerminalById(String id) {
        return mapper.deleteTerminalById(id) > 0;
    }

    public int deleteTerminalByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return mapper.deleteTerminalByIds(ids);
    }

    private static int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static int normalizePageSize(Integer pageSize) {
        if (pageSize == null || pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_LIMIT);
    }

    private static int normalizeOffset(Integer page, int pageSize) {
        int normalizedPage = page == null || page <= 0 ? 1 : page;
        return (normalizedPage - 1) * pageSize;
    }

    private static <E extends Enum<E>> List<String> enumNames(List<E> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .map(Enum::name)
                .toList();
    }

    private static String normalizeKeyword(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim().toLowerCase();
    }
}
