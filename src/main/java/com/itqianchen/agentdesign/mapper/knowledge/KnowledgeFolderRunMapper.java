package com.itqianchen.agentdesign.mapper.knowledge;

import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 知识维护领域扩展与通用任务事实表的联合查询边界。 */
public interface KnowledgeFolderRunMapper {

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

    List<KnowledgeFolderRun> findRuns(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("limit") int limit
    );

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

    long countRuns(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("operations") List<String> operations,
            @Param("statuses") List<String> statuses,
            @Param("keyword") String keyword,
            @Param("timeFrom") Long timeFrom,
            @Param("timeTo") Long timeTo
    );

    List<KnowledgeFolderRun> findLatestRunsByScope();

    KnowledgeFolderRun findLatestRun();

    int updateCompletion(
            @Param("id") String id,
            @Param("scannedCount") int scannedCount,
            @Param("parsedCount") int parsedCount,
            @Param("skippedCount") int skippedCount,
            @Param("failedCount") int failedCount,
            @Param("indexedDocumentCount") long indexedDocumentCount,
            @Param("indexedChunkCount") long indexedChunkCount,
            @Param("failedDocumentCount") long failedDocumentCount,
            @Param("failuresJson") String failuresJson
    );

    int updateFailure(
            @Param("id") String id,
            @Param("errorStage") String errorStage,
            @Param("errorDetail") String errorDetail
    );
}
