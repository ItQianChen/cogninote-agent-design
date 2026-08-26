package com.itqianchen.agentdesign.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.domain.exception.knowledge.KnowledgeMaintenanceException;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.domain.vo.knowledge.MaintenanceTaskPayloadV1;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.index.IndexService;
import com.itqianchen.agentdesign.service.task.DurableTaskFailure;
import com.itqianchen.agentdesign.service.task.DurableTaskScheduler;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class KnowledgeMaintenanceQueueServiceTests {

    private final KnowledgeFolderRunRepository runRepository = mock(KnowledgeFolderRunRepository.class);
    private final DurableTaskRunRepository taskRepository = mock(DurableTaskRunRepository.class);
    private final KnowledgeFolderService folderService = mock(KnowledgeFolderService.class);
    private final IndexService indexService = mock(IndexService.class);
    private final KnowledgeFolderRunService runService = mock(KnowledgeFolderRunService.class);
    private final KnowledgeMaintenanceRunPublisher publisher = mock(KnowledgeMaintenanceRunPublisher.class);
    private final DocumentIdentity documentIdentity = mock(DocumentIdentity.class);
    private final MaintenanceTaskPayloadCodec payloadCodec = new MaintenanceTaskPayloadCodec(new ObjectMapper());
    @SuppressWarnings("unchecked")
    private final ObjectProvider<DurableTaskScheduler> schedulerProvider = mock(ObjectProvider.class);
    private final KnowledgeMaintenanceQueueService service = new KnowledgeMaintenanceQueueService(
            runRepository,
            taskRepository,
            folderService,
            indexService,
            runService,
            publisher,
            new KnowledgeMaintenanceProgressReporter(),
            documentIdentity,
            new DocumentFailureCodec(new ObjectMapper()),
            payloadCodec,
            schedulerProvider
    );

    @Test
    void ingestCompletionPreservesStructuredFailures() {
        DocumentFailureResponse failure = DocumentFailureResponse.of(
                "D:/docs/scanned.pdf",
                DocumentFailureStage.MODEL_CALL,
                DocumentFailureCode.MODEL_AUTH_FAILED,
                "视觉模型鉴权失败。",
                "OPENAI_COMPATIBLE / HTTP 401",
                "检查 API Key。",
                1780000000000L,
                1,
                "OPENAI_COMPATIBLE",
                "qwen3-vl-plus",
                401,
                "invalid_api_key"
        );

        KnowledgeMaintenanceCompletion completion = KnowledgeMaintenanceQueueService.ingestCompletion(
                new IngestDocumentsResponse(1, 0, 0, 1, List.of(failure))
        );

        assertThat(completion.status()).isEqualTo(KnowledgeFolderRunStatus.COMPLETED_WITH_WARNINGS);
        assertThat(completion.failures()).containsExactly(failure);
    }

    @Test
    void enqueueFolderSyncReusesActiveRunWithSamePayload() {
        MaintenanceTaskPayloadV1 payload = new MaintenanceTaskPayloadV1(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                "folder-1",
                KnowledgeFolderRunOperation.SYNC,
                null,
                true,
                null
        );
        KnowledgeFolderRun activeRun = run("run-active", KnowledgeFolderRunStatus.RUNNING);
        when(taskRepository.findActiveByIdempotency(
                KnowledgeMaintenanceQueueService.TASK_TYPE,
                payloadCodec.idempotencyKey(payload)
        )).thenReturn(Optional.of(task("run-active", payload, DurableTaskStatus.RUNNING)));
        when(runRepository.findById("run-active")).thenReturn(Optional.of(activeRun));

        assertThat(service.enqueueFolderSync("folder-1").id()).isEqualTo("run-active");
        verify(runRepository, never()).insertDurable(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void cancelRetryWaitRunUsesDurableStateTransition() {
        KnowledgeFolderRun waiting = run("run-waiting", KnowledgeFolderRunStatus.RETRY_WAIT);
        KnowledgeFolderRun cancelled = run("run-waiting", KnowledgeFolderRunStatus.CANCELLED);
        when(runRepository.findById("run-waiting"))
                .thenReturn(Optional.of(waiting))
                .thenReturn(Optional.of(cancelled));
        when(taskRepository.cancel("run-waiting", "用户取消等待中的维护任务。")).thenReturn(true);
        when(runRepository.findActiveRuns()).thenReturn(List.of());
        when(runRepository.findQueuedRuns()).thenReturn(List.of());
        when(runRepository.findLatestRun()).thenReturn(Optional.of(cancelled));

        assertThat(service.cancel("run-waiting")).isTrue();
        verify(publisher).publishCancelled("run-waiting", com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse.from(cancelled));
    }

    @Test
    void retryRejectsCompletedRun() {
        when(runRepository.findById("run-completed"))
                .thenReturn(Optional.of(run("run-completed", KnowledgeFolderRunStatus.COMPLETED)));

        assertThatThrownBy(() -> service.retry("run-completed"))
                .isInstanceOf(KnowledgeMaintenanceException.class)
                .hasMessageContaining("只能重试失败或中断");
    }

    @Test
    void invalidPayloadIsClassifiedAsInterrupted() {
        DurableTaskRun run = new DurableTaskRun(
                "run-invalid", KnowledgeMaintenanceQueueService.TASK_TYPE,
                KnowledgeMaintenanceQueueService.QUEUE_NAME, "SYNC", DurableTaskStatus.RUNNING,
                "SYNCING", 1, "{\"unexpected\":true}", null, null, true, 1, 3,
                "key", null, "owner", 2L, 1L, 1L, 0, 1, null, null, null,
                1L, 1L, null, null, 1L, 1L
        );

        DurableTaskFailure failure = service.classifyFailure(run, new IllegalStateException("boom"));

        assertThat(failure.status()).isEqualTo(DurableTaskStatus.INTERRUPTED);
        assertThat(failure.code()).isEqualTo("INVALID_PAYLOAD");
    }

    private DurableTaskRun task(String id, MaintenanceTaskPayloadV1 payload, DurableTaskStatus status) {
        return new DurableTaskRun(
                id, KnowledgeMaintenanceQueueService.TASK_TYPE, KnowledgeMaintenanceQueueService.QUEUE_NAME,
                payload.operation().name(), status, status.name(), 1, payloadCodec.encode(payload), null, null,
                true, 1, 3, payloadCodec.idempotencyKey(payload), null, "owner", 2L, 1L, 1L,
                0, 1, null, null, null, 1L, 1L, null, null, 1L, 1L
        );
    }

    private static KnowledgeFolderRun run(String id, KnowledgeFolderRunStatus status) {
        long now = 1780000000000L;
        return new KnowledgeFolderRun(
                id,
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                "folder-1",
                KnowledgeFolderRunOperation.SYNC,
                status,
                0, 0, 0, 0, 0, 0, 0,
                null,
                status.name(),
                0,
                1,
                null,
                now,
                status == KnowledgeFolderRunStatus.QUEUED ? null : now,
                status == KnowledgeFolderRunStatus.COMPLETED ? now + 100 : null,
                status == KnowledgeFolderRunStatus.COMPLETED ? 100L : null,
                null,
                now,
                now
        );
    }
}
