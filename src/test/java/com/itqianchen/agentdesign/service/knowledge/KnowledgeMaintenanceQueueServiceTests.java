package com.itqianchen.agentdesign.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itqianchen.agentdesign.domain.dto.document.DocumentFailureResponse;
import com.itqianchen.agentdesign.domain.dto.document.IngestDocumentsResponse;
import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse;
import com.itqianchen.agentdesign.domain.entity.knowledge.KnowledgeFolderRun;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureCode;
import com.itqianchen.agentdesign.domain.enums.document.DocumentFailureStage;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunOperation;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunScopeType;
import com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus;
import com.itqianchen.agentdesign.domain.exception.knowledge.KnowledgeMaintenanceException;
import com.itqianchen.agentdesign.domain.vo.ingestion.DocumentIdentity;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.service.document.DocumentFailureCodec;
import com.itqianchen.agentdesign.service.index.IndexService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class KnowledgeMaintenanceQueueServiceTests {

    private final KnowledgeFolderRunRepository runRepository = mock(KnowledgeFolderRunRepository.class);
    private final KnowledgeFolderService folderService = mock(KnowledgeFolderService.class);
    private final IndexService indexService = mock(IndexService.class);
    private final KnowledgeFolderRunService runService = mock(KnowledgeFolderRunService.class);
    private final KnowledgeMaintenanceRunPublisher publisher = mock(KnowledgeMaintenanceRunPublisher.class);
    private final KnowledgeMaintenanceProgressReporter progressReporter =
            new KnowledgeMaintenanceProgressReporter(runRepository, publisher);
    private final DocumentIdentity documentIdentity = mock(DocumentIdentity.class);
    private final KnowledgeMaintenanceQueueService service = new KnowledgeMaintenanceQueueService(
            runRepository,
            folderService,
            indexService,
            runService,
            publisher,
            progressReporter,
            documentIdentity,
            new DocumentFailureCodec(new ObjectMapper()),
            Runnable::run
    );

    @Test
    void ingestCompletionPreservesStructuredFailures() {
        DocumentFailureResponse failure = DocumentFailureResponse.of(
                "D:/docs/scanned.pdf",
                DocumentFailureStage.MODEL_CALL,
                DocumentFailureCode.MODEL_AUTH_FAILED,
                "视觉模型鉴权失败。",
                "DASHSCOPE / HTTP 401",
                "检查 API Key。",
                1780000000000L,
                1,
                "DASHSCOPE",
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
    void enqueueFolderSyncReusesActiveRunForSameScopeAndOperation() {
        KnowledgeFolderRun activeRun = run(
                "run-active",
                KnowledgeFolderRunStatus.RUNNING,
                KnowledgeFolderRunOperation.SYNC,
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                "folder-1"
        );
        when(runRepository.findActiveByScopeAndOperation(
                KnowledgeFolderRunScopeType.KNOWLEDGE_FOLDER,
                "folder-1",
                KnowledgeFolderRunOperation.SYNC
        )).thenReturn(Optional.of(activeRun));

        KnowledgeFolderRunResponse response = service.enqueueFolderSync("folder-1");

        assertThat(response.id()).isEqualTo("run-active");
        assertThat(response.status()).isEqualTo(KnowledgeFolderRunStatus.RUNNING);
        verifyNoInteractions(folderService, indexService);
    }

    @Test
    void cancelQueuedRunMarksCancelledAndPublishesTerminalEvent() {
        KnowledgeFolderRun queuedRun = run(
                "run-queued",
                KnowledgeFolderRunStatus.QUEUED,
                KnowledgeFolderRunOperation.REBUILD_INDEX,
                KnowledgeFolderRunScopeType.ALL,
                null
        );
        KnowledgeFolderRun cancelledRun = run(
                "run-queued",
                KnowledgeFolderRunStatus.CANCELLED,
                KnowledgeFolderRunOperation.REBUILD_INDEX,
                KnowledgeFolderRunScopeType.ALL,
                null
        );
        when(runRepository.findById("run-queued"))
                .thenReturn(Optional.of(queuedRun))
                .thenReturn(Optional.of(cancelledRun));
        when(runRepository.findActiveRuns()).thenReturn(List.of());
        when(runRepository.findQueuedRuns()).thenReturn(List.of());
        when(runRepository.findLatestRun()).thenReturn(Optional.of(cancelledRun));

        boolean accepted = service.cancel("run-queued");

        assertThat(accepted).isTrue();
        verify(runRepository).markCancelled("run-queued", "用户取消排队中的维护任务。");
        verify(publisher).publishCancelled("run-queued", KnowledgeFolderRunResponse.from(cancelledRun));
    }

    @Test
    void cancelRunningRunIsRejectedWithoutChangingState() {
        KnowledgeFolderRun runningRun = run(
                "run-running",
                KnowledgeFolderRunStatus.RUNNING,
                KnowledgeFolderRunOperation.REBUILD_INDEX,
                KnowledgeFolderRunScopeType.ALL,
                null
        );
        when(runRepository.findById("run-running")).thenReturn(Optional.of(runningRun));

        assertThatThrownBy(() -> service.cancel("run-running"))
                .isInstanceOf(KnowledgeMaintenanceException.class)
                .hasMessageContaining("只能取消等待中的维护任务");

        verify(runRepository, never()).markCancelling("run-running");
        verify(publisher, never()).cancel("run-running");
    }

    private static KnowledgeFolderRun run(
            String id,
            KnowledgeFolderRunStatus status,
            KnowledgeFolderRunOperation operation,
            KnowledgeFolderRunScopeType scopeType,
            String scopeId
    ) {
        long now = 1780000000000L;
        return new KnowledgeFolderRun(
                id,
                scopeType,
                scopeId,
                operation,
                status,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                status.name(),
                0,
                0,
                null,
                now,
                status == KnowledgeFolderRunStatus.QUEUED ? null : now,
                status == KnowledgeFolderRunStatus.QUEUED ? null : now + 100,
                status == KnowledgeFolderRunStatus.QUEUED ? null : 100L,
                null,
                now,
                now
        );
    }
}
