package com.itqianchen.agentdesign.repository.knowledge;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.mapper.knowledge.KnowledgeFolderRunMapper;
import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库维护运行记录仓储。
 */
@Repository
public class KnowledgeFolderRunRepository {

    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_PAGE_SIZE = 10;

    private final KnowledgeFolderRunMapper mapper;
    private final DurableTaskRunRepository taskRepository;

    /**
     * 注入维护运行记录 Mapper。
     *
     * @param mapper SQLite 运行记录访问接口
     */
    public KnowledgeFolderRunRepository(
            KnowledgeFolderRunMapper mapper,
            DurableTaskRunRepository taskRepository
    ) {
        this.mapper = mapper;
        this.taskRepository = taskRepository;
    }

    /**
     * 保存维护运行记录。
     *
     * @param run 运行记录
     */
    @Transactional
    public void insert(KnowledgeFolderRun run) {
        long queuedAt = run.queuedAt() == null ? run.createdAt() : run.queuedAt();
        taskRepository.insert(new DurableTaskRun(
                run.id(), "KNOWLEDGE_MAINTENANCE", "KNOWLEDGE_MUTATION", run.operation().name(),
                DurableTaskStatus.valueOf(run.status().name()), run.phase(), 0, "{}", null, null,
                false, run.attempt(), run.maxAttempts(), "history:" + run.id(), run.retryOfRunId(),
                null, null, null, run.availableAt(), run.progressCurrent(), run.progressTotal(), run.currentItem(),
                run.errorCode(), run.errorMessage(), queuedAt, run.startedAt(), run.completedAt(), run.durationMs(),
                run.createdAt(), run.updatedAt()
        ));
        mapper.insertRun(run);
    }

    @Transactional
    public void insertDurable(DurableTaskRun task, KnowledgeFolderRun detail) {
        taskRepository.insert(task);
        mapper.insertRun(detail);
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

    public int updateCompletion(
            String id,
            int scannedCount,
            int parsedCount,
            int skippedCount,
            int failedCount,
            long indexedDocumentCount,
            long indexedChunkCount,
            long failedDocumentCount,
            String failuresJson
    ) {
        return mapper.updateCompletion(
                id,
                scannedCount,
                parsedCount,
                skippedCount,
                failedCount,
                indexedDocumentCount,
                indexedChunkCount,
                failedDocumentCount,
                failuresJson
        );
    }

    public int updateFailure(String id, String errorStage, String errorDetail) {
        return mapper.updateFailure(id, errorStage, errorDetail);
    }

    /**
     * 删除指定范围的运行记录。
     *
     * @param scopeType 范围类型
     * @param scopeId 范围 ID；全库范围为空
     * @return 删除的记录数量
     */
    public int deleteByScope(KnowledgeFolderRunScopeType scopeType, String scopeId) {
        return taskRepository.deleteTerminalByScopeExcept(scopeType.name(), scopeId, "");
    }

    public int deleteByScopeExcept(KnowledgeFolderRunScopeType scopeType, String scopeId, String excludedId) {
        return taskRepository.deleteTerminalByScopeExcept(scopeType.name(), scopeId, excludedId);
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
        return taskRepository.deleteTerminalById(id);
    }

    public int deleteTerminalByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        return taskRepository.deleteTerminalByIds(ids);
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
