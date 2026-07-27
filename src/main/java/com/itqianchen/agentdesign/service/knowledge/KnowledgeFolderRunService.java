package com.itqianchen.agentdesign.service.knowledge;


import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRebuildResponse;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 维护知识库目录操作历史。
 *
 * <p>运行记录用于解释最近一次导入、同步和重建结果，不参与导入事务的正确性判断。</p>
 */
@Service
public class KnowledgeFolderRunService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeFolderRunService.class);
    private static final ThreadLocal<Boolean> RECORDING_SUPPRESSED = ThreadLocal.withInitial(() -> false);

    private final KnowledgeFolderRunRepository runRepository;
    private final DocumentFailureCodec failureCodec;

    /**
     * 注入运行记录依赖。
     *
     * @param runRepository 运行记录仓储
     * @param failureCodec 失败诊断编解码器
     */
    public KnowledgeFolderRunService(
            KnowledgeFolderRunRepository runRepository,
            DocumentFailureCodec failureCodec
    ) {
        this.runRepository = runRepository;
        this.failureCodec = failureCodec;
    }

    /**
     * 在维护队列接管任务生命周期时临时抑制旧入口的完成日志写入。
     *
     * <p>旧 API 仍需要同步记录完成历史；新队列执行时已有 QUEUED/RUNNING run，底层服务不能再插入
     * 第二条完成记录，否则前端历史和当前任务会再次分裂。</p>
     *
     * @param action 需要执行的旧维护动作
     * @return 动作返回值
     * @param <T> 返回值类型
     */
    public <T> T withoutRecording(Supplier<T> action) {
        boolean previous = RECORDING_SUPPRESSED.get();
        RECORDING_SUPPRESSED.set(true);
        try {
            return action.get();
        } finally {
            RECORDING_SUPPRESSED.set(previous);
        }
    }

    public void withoutRecording(Runnable action) {
        withoutRecording(() -> {
            action.run();
            return null;
        });
    }

    /**
     * 记录目录导入结果。
     *
     * @param folderId 目录 ID
     * @param response 导入响应
     * @param startedAt 操作开始时间戳
     */
    public void recordImport(String folderId, IngestDocumentsResponse response, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        recordIngestRun(KnowledgeFolderRunOperation.IMPORT, folderId, response, startedAt);
    }

    /**
     * 记录目录同步结果。
     *
     * @param folderId 目录 ID
     * @param response 同步响应
     * @param startedAt 操作开始时间戳
     */
    public void recordSync(String folderId, IngestDocumentsResponse response, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        recordIngestRun(KnowledgeFolderRunOperation.SYNC, folderId, response, startedAt);
    }

    /**
     * 记录目录重建结果。
     *
     * @param folderId 目录 ID
     * @param response 重建响应
     * @param startedAt 操作开始时间戳
     */
    public void recordFolderRebuild(String folderId, KnowledgeFolderRebuildResponse response, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        long completedAt = System.currentTimeMillis();
        insert(new KnowledgeFolderRun(
                UUID.randomUUID().toString(),
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                KnowledgeFolderRunOperation.REBUILD_INDEX,
                statusFor(response.failedCount(), response.failedDocumentCount(), response.failures()),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount(),
                response.indexedDocumentCount(),
                response.indexedChunkCount(),
                response.failedDocumentCount(),
                failureCodec.encodeFailures(response.failures()),
                "COMPLETED",
                response.indexedDocumentCount(),
                response.indexedDocumentCount(),
                null,
                startedAt,
                startedAt,
                completedAt,
                completedAt - startedAt,
                null,
                completedAt,
                completedAt
        ));
    }

    /**
     * 记录全库索引重建结果。
     *
     * @param response 重建响应
     * @param startedAt 操作开始时间戳
     */
    public void recordAllIndexRebuild(RebuildIndexResponse response, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        long completedAt = System.currentTimeMillis();
        insert(new KnowledgeFolderRun(
                UUID.randomUUID().toString(),
                KnowledgeFolderRunScopeType.ALL,
                null,
                KnowledgeFolderRunOperation.REBUILD_INDEX,
                statusFor(0, response.failedDocumentCount(), response.failures()),
                0,
                0,
                0,
                0,
                response.indexedDocumentCount(),
                response.indexedChunkCount(),
                response.failedDocumentCount(),
                failureCodec.encodeFailures(response.failures()),
                "COMPLETED",
                response.indexedDocumentCount(),
                response.indexedDocumentCount(),
                null,
                startedAt,
                startedAt,
                completedAt,
                completedAt - startedAt,
                null,
                completedAt,
                completedAt
        ));
    }

    /**
     * 记录目录启用或停用结果。
     *
     * @param folderId 目录 ID
     * @param enabled 是否启用
     * @param rebuildResponse 启用时的索引重建结果；停用时为空
     * @param startedAt 操作开始时间戳
     */
    public void recordEnabled(String folderId, boolean enabled, RebuildIndexResponse rebuildResponse, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        long completedAt = System.currentTimeMillis();
        long failedDocumentCount = rebuildResponse == null ? 0 : rebuildResponse.failedDocumentCount();
        insert(new KnowledgeFolderRun(
                UUID.randomUUID().toString(),
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                enabled ? KnowledgeFolderRunOperation.ENABLE : KnowledgeFolderRunOperation.DISABLE,
                statusFor(0, failedDocumentCount),
                0,
                0,
                0,
                0,
                rebuildResponse == null ? 0 : rebuildResponse.indexedDocumentCount(),
                rebuildResponse == null ? 0 : rebuildResponse.indexedChunkCount(),
                failedDocumentCount,
                null,
                "COMPLETED",
                rebuildResponse == null ? 0 : rebuildResponse.indexedDocumentCount(),
                rebuildResponse == null ? 0 : rebuildResponse.indexedDocumentCount(),
                null,
                startedAt,
                startedAt,
                completedAt,
                completedAt - startedAt,
                null,
                completedAt,
                completedAt
        ));
    }

    /**
     * 记录目录删除结果。
     *
     * @param folderId 目录 ID
     * @param deletedDocuments 删除的应用内文档数量
     * @param startedAt 操作开始时间戳
     */
    public void recordDelete(String folderId, int deletedDocuments, long startedAt) {
        if (isRecordingSuppressed()) {
            return;
        }
        long completedAt = System.currentTimeMillis();
        insert(new KnowledgeFolderRun(
                UUID.randomUUID().toString(),
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                KnowledgeFolderRunOperation.DELETE,
                KnowledgeFolderRunStatus.COMPLETED,
                deletedDocuments,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                "COMPLETED",
                deletedDocuments,
                deletedDocuments,
                null,
                startedAt,
                startedAt,
                completedAt,
                completedAt - startedAt,
                null,
                completedAt,
                completedAt
        ));
    }

    /**
     * 删除某个目录的维护运行记录。
     *
     * <p>目录删除后，该 scope 已不再有可展示对象；保留历史会让健康页出现孤儿可信状态数据。</p>
     *
     * @param folderId 目录 ID
     */
    public void deleteFolderRuns(String folderId) {
        deleteFolderRuns(folderId, null);
    }

    public void deleteFolderRuns(String folderId, String retainedRunId) {
        try {
            int deleted = retainedRunId == null
                    ? runRepository.deleteByScope(KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER, folderId)
                    : runRepository.deleteByScopeExcept(
                            KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                            folderId,
                            retainedRunId
                    );
            log.info("knowledge_folder_runs_deleted folderId={} deleted={}", folderId, deleted);
        } catch (RuntimeException ex) {
            log.warn("knowledge_folder_runs_delete_failed folderId={}", folderId, ex);
        }
    }

    private void recordIngestRun(
            KnowledgeFolderRunOperation operation,
            String folderId,
            IngestDocumentsResponse response,
            long startedAt
    ) {
        long completedAt = System.currentTimeMillis();
        insert(new KnowledgeFolderRun(
                UUID.randomUUID().toString(),
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                operation,
                statusFor(response.failedCount(), 0, response.failures()),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount(),
                0,
                0,
                0,
                failureCodec.encodeFailures(response.failures()),
                "COMPLETED",
                response.scannedCount(),
                response.scannedCount(),
                null,
                startedAt,
                startedAt,
                completedAt,
                completedAt - startedAt,
                null,
                completedAt,
                completedAt
        ));
    }

    private static boolean isRecordingSuppressed() {
        return Boolean.TRUE.equals(RECORDING_SUPPRESSED.get());
    }

    private void insert(KnowledgeFolderRun run) {
        try {
            runRepository.insert(run);
        } catch (RuntimeException ex) {
            /*
             * 运行记录是可丢失的诊断信息。写入失败不能反向破坏已经完成的目录导入、同步或重建。
             */
            log.warn("knowledge_folder_run_record_failed operation={} scopeType={} scopeId={}",
                    run.operation(),
                    run.scopeType(),
                    run.scopeId(),
                    ex
            );
        }
    }

    private static KnowledgeFolderRunStatus statusFor(long failedCount, long failedDocumentCount) {
        return statusFor(failedCount, failedDocumentCount, List.of());
    }

    private static KnowledgeFolderRunStatus statusFor(
            long failedCount,
            long failedDocumentCount,
            List<?> failures
    ) {
        if (failedCount > 0 || failedDocumentCount > 0 || failures != null && !failures.isEmpty()) {
            return KnowledgeFolderRunStatus.COMPLETED_WITH_WARNINGS;
        }
        return KnowledgeFolderRunStatus.COMPLETED;
    }
}
