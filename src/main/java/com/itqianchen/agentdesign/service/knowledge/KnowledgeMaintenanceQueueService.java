package com.itqianchen.agentdesign.service.knowledge;

import com.itqianchen.agentdesign.common.api.ResourceNotFoundException;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.index.RebuildIndexResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRebuildResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeMaintenanceQueueResponse;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.domain.exception.ingestion.DocumentParseException;
import com.itqianchen.agentdesign.domain.exception.knowledge.KnowledgeMaintenanceException;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.vo.knowledge.MaintenanceTaskPayloadV1;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureClassifier;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.index.IndexService;
import com.itqianchen.agentdesign.service.task.DurableTaskContext;
import com.itqianchen.agentdesign.service.task.DurableTaskFailure;
import com.itqianchen.agentdesign.service.task.DurableTaskHandler;
import com.itqianchen.agentdesign.service.task.DurableTaskOutcome;
import com.itqianchen.agentdesign.service.task.DurableTaskScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 知识维护 API 与通用耐久任务核心之间的领域适配器。
 *
 * <p>任务参数和生命周期只保存在 SQLite；本类不保存进程内任务副本，也不判断 worker 是否空闲。</p>
 */
@Service
public class KnowledgeMaintenanceQueueService implements DurableTaskHandler {

    static final String TASK_TYPE = "KNOWLEDGE_MAINTENANCE";
    static final String QUEUE_NAME = "KNOWLEDGE_MUTATION";
    static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(KnowledgeMaintenanceQueueService.class);

    private final KnowledgeFolderRunRepository runRepository;
    private final DurableTaskRunRepository taskRepository;
    private final KnowledgeFolderService folderService;
    private final IndexService indexService;
    private final KnowledgeFolderRunService runService;
    private final KnowledgeMaintenanceRunPublisher publisher;
    private final KnowledgeMaintenanceProgressReporter progressReporter;
    private final DocumentIdentity documentIdentity;
    private final DocumentFailureCodec failureCodec;
    private final MaintenanceTaskPayloadCodec payloadCodec;
    private final ObjectProvider<DurableTaskScheduler> schedulerProvider;

    public KnowledgeMaintenanceQueueService(
            KnowledgeFolderRunRepository runRepository,
            DurableTaskRunRepository taskRepository,
            KnowledgeFolderService folderService,
            IndexService indexService,
            KnowledgeFolderRunService runService,
            KnowledgeMaintenanceRunPublisher publisher,
            KnowledgeMaintenanceProgressReporter progressReporter,
            DocumentIdentity documentIdentity,
            DocumentFailureCodec failureCodec,
            MaintenanceTaskPayloadCodec payloadCodec,
            ObjectProvider<DurableTaskScheduler> schedulerProvider
    ) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.folderService = folderService;
        this.indexService = indexService;
        this.runService = runService;
        this.publisher = publisher;
        this.progressReporter = progressReporter;
        this.documentIdentity = documentIdentity;
        this.failureCodec = failureCodec;
        this.payloadCodec = payloadCodec;
        this.schedulerProvider = schedulerProvider;
    }

    @Override
    public String taskType() {
        return TASK_TYPE;
    }

    @Override
    public String queueName() {
        return QUEUE_NAME;
    }

    @Override
    public boolean supports(String operation, int payloadVersion) {
        if (payloadVersion != MaintenanceTaskPayloadCodec.PAYLOAD_VERSION) {
            return false;
        }
        try {
            KnowledgeFolderRunOperation.valueOf(operation);
            return true;
        } catch (IllegalArgumentException | NullPointerException ex) {
            return false;
        }
    }

    public KnowledgeFolderRunResponse enqueueImport(String folderPath, boolean recursive) {
        Path folder = Path.of(folderPath).toAbsolutePath().normalize();
        if (!Files.isDirectory(folder)) {
            throw new DocumentParseException("Folder does not exist or is not a directory: " + folder);
        }
        String normalizedPath = folder.toString();
        return enqueue(new MaintenanceTaskPayloadV1(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                documentIdentity.idForPath(normalizedPath),
                KnowledgeFolderRunOperation.IMPORT,
                normalizedPath,
                recursive,
                null
        ));
    }

    public KnowledgeFolderRunResponse enqueueRebuildAllIndex() {
        return enqueue(allTask(KnowledgeFolderRunOperation.REBUILD_INDEX));
    }

    public KnowledgeFolderRunResponse enqueueRepairAllIndex() {
        return enqueue(allTask(KnowledgeFolderRunOperation.REPAIR_INDEX));
    }

    public KnowledgeFolderRunResponse enqueueFolderSync(String folderId) {
        return enqueue(folderTask(folderId, KnowledgeFolderRunOperation.SYNC));
    }

    public KnowledgeFolderRunResponse enqueueFolderReparse(String folderId) {
        return enqueue(folderTask(folderId, KnowledgeFolderRunOperation.REPARSE));
    }

    public KnowledgeFolderRunResponse enqueueFolderRebuild(String folderId) {
        return enqueue(folderTask(folderId, KnowledgeFolderRunOperation.REBUILD_INDEX));
    }

    public KnowledgeFolderRunResponse enqueueFolderRepairIndex(String folderId) {
        return enqueue(folderTask(folderId, KnowledgeFolderRunOperation.REPAIR_INDEX));
    }

    public KnowledgeFolderRunResponse enqueueFolderEnabled(String folderId, boolean enabled) {
        return enqueue(new MaintenanceTaskPayloadV1(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                enabled ? KnowledgeFolderRunOperation.ENABLE : KnowledgeFolderRunOperation.DISABLE,
                null,
                true,
                enabled
        ));
    }

    public KnowledgeFolderRunResponse enqueueFolderDelete(String folderId) {
        return enqueue(folderTask(folderId, KnowledgeFolderRunOperation.DELETE));
    }

    public KnowledgeMaintenanceQueueResponse queue() {
        return new KnowledgeMaintenanceQueueResponse(
                runRepository.findActiveRuns().stream().map(KnowledgeFolderRunResponse::from).toList(),
                withQueuePositions(runRepository.findQueuedRuns()),
                runRepository.findLatestRun().map(KnowledgeFolderRunResponse::from).orElse(null)
        );
    }

    public KnowledgeFolderRunResponse getRun(String runId) {
        return runRepository.findById(runId)
                .map(KnowledgeFolderRunResponse::from)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge maintenance run not found: " + runId));
    }

    public SseEmitter subscribe(String runId) {
        KnowledgeFolderRunResponse snapshot = getRun(runId);
        return publisher.subscribe(runId, snapshot, isTerminal(snapshot.status()));
    }

    public boolean cancel(String runId) {
        KnowledgeFolderRun run = requireRun(runId);
        if (run.status() != KnowledgeFolderRunStatus.QUEUED
                && run.status() != KnowledgeFolderRunStatus.RETRY_WAIT) {
            throw new KnowledgeMaintenanceException("只能取消等待中的维护任务；正在运行的任务会自动执行到安全完成点。");
        }
        if (!taskRepository.cancel(runId, "用户取消等待中的维护任务。")) {
            throw new KnowledgeMaintenanceException("任务状态已变化，请刷新后重试。");
        }
        runRepository.findById(runId).map(KnowledgeFolderRunResponse::from)
                .ifPresent(response -> publisher.publishCancelled(runId, response));
        publisher.publishQueueUpdated(queue());
        wakeScheduler();
        return true;
    }

    public KnowledgeFolderRunResponse retry(String runId) {
        KnowledgeFolderRun previous = requireRun(runId);
        if (previous.status() != KnowledgeFolderRunStatus.FAILED
                && previous.status() != KnowledgeFolderRunStatus.INTERRUPTED) {
            throw new KnowledgeMaintenanceException("只能重试失败或中断的维护任务。");
        }
        DurableTaskRun previousTask = taskRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Durable task not found: " + runId));
        MaintenanceTaskPayloadV1 payload;
        try {
            payload = decodeAndVerify(previousTask);
        } catch (UnsupportedMaintenancePayloadException ex) {
            throw new KnowledgeMaintenanceException("该任务的参数版本不受当前版本支持，无法重试。");
        }
        return enqueue(payload, runId);
    }

    private synchronized KnowledgeFolderRunResponse enqueue(MaintenanceTaskPayloadV1 payload) {
        return enqueue(payload, null);
    }

    private synchronized KnowledgeFolderRunResponse enqueue(MaintenanceTaskPayloadV1 payload, String retryOfRunId) {
        String idempotencyKey = payloadCodec.idempotencyKey(payload);
        return taskRepository.findActiveByIdempotency(TASK_TYPE, idempotencyKey)
                .map(task -> getRun(task.id()))
                .orElseGet(() -> createQueuedRun(payload, idempotencyKey, retryOfRunId));
    }

    private KnowledgeFolderRunResponse createQueuedRun(
            MaintenanceTaskPayloadV1 payload,
            String idempotencyKey,
            String retryOfRunId
    ) {
        long now = System.currentTimeMillis();
        String runId = UUID.randomUUID().toString();
        DurableTaskRun task = new DurableTaskRun(
                runId,
                TASK_TYPE,
                QUEUE_NAME,
                payload.operation().name(),
                DurableTaskStatus.QUEUED,
                "QUEUED",
                MaintenanceTaskPayloadCodec.PAYLOAD_VERSION,
                payloadCodec.encode(payload),
                null,
                null,
                true,
                0,
                MAX_ATTEMPTS,
                idempotencyKey,
                retryOfRunId,
                null,
                null,
                null,
                now,
                0,
                1,
                currentItem(payload),
                null,
                null,
                now,
                null,
                null,
                null,
                now,
                now
        );
        KnowledgeFolderRun detail = new KnowledgeFolderRun(
                runId,
                payload.scopeType(),
                payload.scopeId(),
                payload.operation(),
                KnowledgeFolderRunStatus.QUEUED,
                0, 0, 0, 0, 0, 0, 0,
                null,
                "QUEUED",
                0,
                1,
                currentItem(payload),
                now,
                null,
                null,
                null,
                null,
                now,
                now
        );
        try {
            runRepository.insertDurable(task, detail);
        } catch (DataIntegrityViolationException ex) {
            return taskRepository.findActiveByIdempotency(TASK_TYPE, idempotencyKey)
                    .map(active -> getRun(active.id()))
                    .orElseThrow(() -> ex);
        }
        KnowledgeFolderRunResponse response = getRun(runId);
        publisher.publishQueued(runId, response);
        wakeScheduler();
        return response;
    }

    @Override
    public DurableTaskOutcome execute(DurableTaskRun run, DurableTaskContext context) {
        MaintenanceTaskPayloadV1 payload = decodeAndVerify(run);
        context.progress(phaseFor(payload.operation()), 0, 1, currentItem(payload), run.checkpointJson());
        KnowledgeMaintenanceCompletion completion = progressReporter.withRun(
                context,
                () -> executeOperation(payload, context.runId())
        );
        int updated = runRepository.updateCompletion(
                run.id(),
                completion.scannedCount(),
                completion.parsedCount(),
                completion.skippedCount(),
                completion.failedCount(),
                completion.indexedDocumentCount(),
                completion.indexedChunkCount(),
                completion.failedDocumentCount(),
                failureCodec.encodeFailures(completion.failures())
        );
        if (updated == 0) {
            throw new IllegalStateException("Knowledge maintenance detail is missing: " + run.id());
        }
        DurableTaskStatus status = completion.status() == KnowledgeFolderRunStatus.COMPLETED_WITH_WARNINGS
                ? DurableTaskStatus.COMPLETED_WITH_WARNINGS
                : DurableTaskStatus.COMPLETED;
        return new DurableTaskOutcome(status, null, completion.progressTotal(), completion.progressTotal());
    }

    @Override
    public DurableTaskFailure classifyFailure(DurableTaskRun run, RuntimeException exception) {
        if (exception instanceof UnsupportedMaintenancePayloadException) {
            return DurableTaskFailure.interrupted("INVALID_PAYLOAD", exception.getMessage());
        }
        MaintenanceTaskPayloadV1 payload;
        try {
            payload = decodeAndVerify(run);
        } catch (UnsupportedMaintenancePayloadException decodeFailure) {
            return DurableTaskFailure.interrupted("INVALID_PAYLOAD", decodeFailure.getMessage());
        }
        DocumentFailureResponse failure = classifyRunFailure(payload, exception);
        runRepository.updateFailure(run.id(), failure.stage(), failure.detail());
        return DurableTaskFailure.failed(failure.code(), failure.message());
    }

    @Override
    public void onStarted(DurableTaskRun run) {
        publishSnapshot(run.id(), publisher::publishStarted);
    }

    @Override
    public void onProgress(DurableTaskRun run) {
        publishSnapshot(run.id(), publisher::publishProgress);
    }

    @Override
    public void onCompleted(DurableTaskRun run) {
        publishSnapshot(run.id(), publisher::publishCompleted);
        publisher.publishQueueUpdated(queue());
    }

    @Override
    public void onFailed(DurableTaskRun run) {
        publishSnapshot(run.id(), publisher::publishFailed);
        publisher.publishQueueUpdated(queue());
    }

    private void publishSnapshot(String runId, RunEventPublisher eventPublisher) {
        runRepository.findById(runId).map(KnowledgeFolderRunResponse::from)
                .ifPresent(response -> eventPublisher.publish(runId, response));
    }

    private MaintenanceTaskPayloadV1 decodeAndVerify(DurableTaskRun run) {
        MaintenanceTaskPayloadV1 payload = payloadCodec.decode(run.payloadVersion(), run.payloadJson());
        if (!payload.operation().name().equals(run.operation())) {
            throw new UnsupportedMaintenancePayloadException("维护任务 operation 与 payload 不一致。");
        }
        return payload;
    }

    private KnowledgeMaintenanceCompletion executeOperation(MaintenanceTaskPayloadV1 payload, String runId) {
        return switch (payload.operation()) {
            case IMPORT -> importFolder(payload);
            case SYNC -> syncFolder(payload);
            case REPARSE -> reparseFolder(payload);
            case REPAIR_INDEX -> payload.scopeType() == KnowledgeFolderRunScopeType.ALL
                    ? repairAllIndex()
                    : repairFolderIndex(payload);
            case REBUILD_INDEX -> payload.scopeType() == KnowledgeFolderRunScopeType.ALL
                    ? rebuildAllIndex()
                    : rebuildFolder(payload);
            case ENABLE, DISABLE -> setFolderEnabled(payload);
            case DELETE -> deleteFolder(payload, runId);
        };
    }

    private KnowledgeMaintenanceCompletion importFolder(MaintenanceTaskPayloadV1 payload) {
        IngestDocumentsResponse response = runService.withoutRecording(
                () -> folderService.importFolder(payload.folderPath(), payload.recursive())
        );
        return ingestCompletion(response);
    }

    private KnowledgeMaintenanceCompletion syncFolder(MaintenanceTaskPayloadV1 payload) {
        return ingestCompletion(runService.withoutRecording(() -> folderService.syncFolder(payload.scopeId())));
    }

    private KnowledgeMaintenanceCompletion reparseFolder(MaintenanceTaskPayloadV1 payload) {
        return ingestCompletion(runService.withoutRecording(() -> folderService.reparseFolder(payload.scopeId())));
    }

    private KnowledgeMaintenanceCompletion rebuildFolder(MaintenanceTaskPayloadV1 payload) {
        KnowledgeFolderRebuildResponse response = runService.withoutRecording(
                () -> folderService.rebuildFolder(payload.scopeId())
        );
        return new KnowledgeMaintenanceCompletion(
                statusFor(response.failedCount(), response.failedDocumentCount()),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount(),
                response.indexedDocumentCount(),
                response.indexedChunkCount(),
                response.failedDocumentCount(),
                response.failures(),
                Math.max(1, response.scannedCount())
        );
    }

    private KnowledgeMaintenanceCompletion repairFolderIndex(MaintenanceTaskPayloadV1 payload) {
        return indexCompletion(runService.withoutRecording(() -> folderService.repairFolderIndex(payload.scopeId())));
    }

    private KnowledgeMaintenanceCompletion rebuildAllIndex() {
        return indexCompletion(runService.withoutRecording(indexService::rebuild));
    }

    private KnowledgeMaintenanceCompletion repairAllIndex() {
        return indexCompletion(runService.withoutRecording(indexService::repair));
    }

    private KnowledgeMaintenanceCompletion setFolderEnabled(MaintenanceTaskPayloadV1 payload) {
        runService.withoutRecording(() -> folderService.setEnabled(payload.scopeId(), Boolean.TRUE.equals(payload.enabled())));
        return KnowledgeMaintenanceCompletion.simple();
    }

    private KnowledgeMaintenanceCompletion deleteFolder(MaintenanceTaskPayloadV1 payload, String runId) {
        try {
            runService.withoutRecording(() -> folderService.deleteFolder(payload.scopeId(), runId));
        } catch (ResourceNotFoundException ex) {
            // DELETE 重放时目标已不存在等价于副作用已经完成，当前 run 仍保留为审计记录。
            runRepository.deleteByScopeExcept(payload.scopeType(), payload.scopeId(), runId);
        }
        return KnowledgeMaintenanceCompletion.simple();
    }

    private static KnowledgeMaintenanceCompletion indexCompletion(RebuildIndexResponse response) {
        return new KnowledgeMaintenanceCompletion(
                statusFor(0, response.failedDocumentCount(), response.failures()),
                0, 0, 0, 0,
                response.indexedDocumentCount(),
                response.indexedChunkCount(),
                response.failedDocumentCount(),
                response.failures(),
                Math.max(1, response.indexedDocumentCount())
        );
    }

    static KnowledgeMaintenanceCompletion ingestCompletion(IngestDocumentsResponse response) {
        return new KnowledgeMaintenanceCompletion(
                statusFor(response.failedCount(), 0, response.failures()),
                response.scannedCount(),
                response.parsedCount(),
                response.skippedCount(),
                response.failedCount(),
                0, 0, 0,
                response.failures(),
                Math.max(1, response.scannedCount())
        );
    }

    private KnowledgeFolderRun requireRun(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Knowledge maintenance run not found: " + runId));
    }

    private void wakeScheduler() {
        DurableTaskScheduler scheduler = schedulerProvider.getIfAvailable();
        if (scheduler != null) {
            scheduler.wake();
        }
    }

    private static KnowledgeFolderRunStatus statusFor(
            long failedCount,
            long failedDocumentCount,
            List<DocumentFailureResponse> failures
    ) {
        return failedCount > 0 || failedDocumentCount > 0 || failures != null && !failures.isEmpty()
                ? KnowledgeFolderRunStatus.COMPLETED_WITH_WARNINGS
                : KnowledgeFolderRunStatus.COMPLETED;
    }

    private static KnowledgeFolderRunStatus statusFor(long failedCount, long failedDocumentCount) {
        return statusFor(failedCount, failedDocumentCount, List.of());
    }

    private static DocumentFailureResponse classifyRunFailure(
            MaintenanceTaskPayloadV1 payload,
            RuntimeException exception
    ) {
        DocumentFailureStage stage = switch (payload.operation()) {
            case REBUILD_INDEX, REPAIR_INDEX -> DocumentFailureStage.INDEX;
            case IMPORT, SYNC, REPARSE -> DocumentFailureStage.SCAN;
            default -> DocumentFailureStage.UNKNOWN;
        };
        Path path = payload.folderPath() == null || payload.folderPath().isBlank()
                ? null
                : Path.of(payload.folderPath()).toAbsolutePath().normalize();
        return DocumentFailureClassifier.classify(path, stage, exception, System.currentTimeMillis());
    }

    private static MaintenanceTaskPayloadV1 allTask(KnowledgeFolderRunOperation operation) {
        return new MaintenanceTaskPayloadV1(KnowledgeFolderRunScopeType.ALL, null, operation, null, true, null);
    }

    private static MaintenanceTaskPayloadV1 folderTask(String folderId, KnowledgeFolderRunOperation operation) {
        return new MaintenanceTaskPayloadV1(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                folderId,
                operation,
                null,
                true,
                null
        );
    }

    private static String phaseFor(KnowledgeFolderRunOperation operation) {
        return switch (operation) {
            case IMPORT -> "IMPORTING";
            case SYNC -> "SYNCING";
            case REPARSE -> "REPARSING";
            case REPAIR_INDEX, REBUILD_INDEX -> "INDEXING";
            case ENABLE -> "ENABLING";
            case DISABLE -> "DISABLING";
            case DELETE -> "DELETING";
        };
    }

    private static String currentItem(MaintenanceTaskPayloadV1 payload) {
        if (payload.folderPath() != null && !payload.folderPath().isBlank()) {
            return payload.folderPath();
        }
        return payload.scopeType() == KnowledgeFolderRunScopeType.ALL ? "全库" : payload.scopeId();
    }

    private static List<KnowledgeFolderRunResponse> withQueuePositions(List<KnowledgeFolderRun> runs) {
        int[] position = {1};
        return runs.stream()
                .map(run -> KnowledgeFolderRunResponse.from(run).withQueuePosition(position[0]++))
                .toList();
    }

    private static boolean isTerminal(KnowledgeFolderRunStatus status) {
        return status != KnowledgeFolderRunStatus.QUEUED
                && status != KnowledgeFolderRunStatus.RUNNING
                && status != KnowledgeFolderRunStatus.RETRY_WAIT
                && status != KnowledgeFolderRunStatus.CANCELLING;
    }

    @FunctionalInterface
    private interface RunEventPublisher {
        void publish(String runId, Object response);
    }
}
