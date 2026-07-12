package com.itqianchen.agentdesign.mapper.knowledge;

import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/**
 * 知识库维护运行记录的 MyBatis SQL 边界。
 */
public interface KnowledgeFolderRunMapper {

    /**
     * 插入维护运行记录。
     *
     * @param run 运行记录
     */
    void insertRun(KnowledgeFolderRun run);

    KnowledgeFolderRun findById(@Param("id") String id);

    KnowledgeFolderRun findActiveByScopeAndOperation(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("operation") String operation
    );

    List<KnowledgeFolderRun> findActiveRuns();

    List<KnowledgeFolderRun> findQueuedRuns();

    List<KnowledgeFolderRun> findQueueRuns();

    /**
     * 查询指定范围最近的维护运行记录。
     *
     * @param scopeType 范围类型；为空时不限制
     * @param scopeId 范围 ID；全库范围为空
     * @param limit 最大返回数量
     * @return 运行记录列表
     */
    List<KnowledgeFolderRun> findRuns(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("limit") int limit
    );

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
     * @param limit 每页数量
     * @param offset 偏移量
     * @return 当前页运行记录
     */
    List<KnowledgeFolderRun> findRunsPage(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("operations") List<String> operations,
            @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword,
            @Param("timeFrom") Long timeFrom,
            @Param("timeTo") Long timeTo,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    /**
     * 统计维护运行记录数量。
     *
     * @param scopeType 范围类型；为空时不限制
     * @param scopeId 范围 ID；全库范围为空
     * @param operations 操作类型过滤；为空时不限制
     * @param statuses 状态过滤；为空时不限制
     * @param keyword 模糊关键词，匹配任务 ID、目录名、目录路径、当前项和错误信息
     * @param timeFrom 起始时间戳；为空时不限制
     * @param timeTo 结束时间戳；为空时不限制
     * @return 记录数量
     */
    long countRuns(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("operations") List<String> operations,
            @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword,
            @Param("timeFrom") Long timeFrom,
            @Param("timeTo") Long timeTo
    );

    /**
     * 查询每个 scope 的最近一次运行记录。
     *
     * @return 每个 scope 的最近维护记录
     */
    List<KnowledgeFolderRun> findLatestRunsByScope();

    KnowledgeFolderRun findLatestRun();

    int markStarted(
            @Param("id") String id,
            @Param("phase") String phase,
            @Param("progressTotal") long progressTotal,
            @Param("currentItem") String currentItem,
            @Param("startedAt") long startedAt
    );

    int updateProgress(
            @Param("id") String id,
            @Param("phase") String phase,
            @Param("progressCurrent") long progressCurrent,
            @Param("progressTotal") long progressTotal,
            @Param("currentItem") String currentItem,
            @Param("updatedAt") long updatedAt
    );

    int markCancelling(
            @Param("id") String id,
            @Param("updatedAt") long updatedAt
    );

    int markCancelled(
            @Param("id") String id,
            @Param("message") String message,
            @Param("completedAt") long completedAt
    );

    int markCompleted(
            @Param("id") String id,
            @Param("status") String status,
            @Param("scannedCount") int scannedCount,
            @Param("parsedCount") int parsedCount,
            @Param("skippedCount") int skippedCount,
            @Param("failedCount") int failedCount,
            @Param("indexedDocumentCount") long indexedDocumentCount,
            @Param("indexedChunkCount") long indexedChunkCount,
            @Param("failedDocumentCount") long failedDocumentCount,
            @Param("failuresJson") String failuresJson,
            @Param("progressCurrent") long progressCurrent,
            @Param("progressTotal") long progressTotal,
            @Param("completedAt") long completedAt
    );

    int markFailed(
            @Param("id") String id,
            @Param("message") String message,
            @Param("errorStage") String errorStage,
            @Param("errorCode") String errorCode,
            @Param("errorDetail") String errorDetail,
            @Param("completedAt") long completedAt
    );

    int cleanupInterruptedRuns(
            @Param("completedAt") long completedAt,
            @Param("queuedMessage") String queuedMessage,
            @Param("runningMessage") String runningMessage
    );

    /**
     * 删除指定 scope 的维护运行记录。
     *
     * @param scopeType 范围类型
     * @param scopeId 范围 ID；全库范围为空
     * @return 删除的记录数量
     */
    int deleteByScope(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId
    );

    /**
     * 删除一条终态维护历史记录。
     *
     * <p>QUEUED/RUNNING/CANCELLING 是队列事实源，不能通过历史清理接口删除。</p>
     *
     * @param id 运行记录 ID
     * @return 受影响行数
     */
    int deleteTerminalById(@Param("id") String id);

    /**
     * 批量删除终态维护历史记录。
     *
     * @param ids 运行记录 ID 列表
     * @return 受影响行数
     */
    int deleteTerminalByIds(@Param("ids") List<String> ids);
}
