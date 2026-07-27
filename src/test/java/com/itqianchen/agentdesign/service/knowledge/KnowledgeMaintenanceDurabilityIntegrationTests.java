package com.itqianchen.agentdesign.service.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.dto.knowledge.KnowledgeFolderRunResponse;
import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.repository.knowledge.KnowledgeFolderRunRepository;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import com.itqianchen.agentdesign.service.task.DurableTaskContext;
import com.itqianchen.agentdesign.service.task.DurableTaskOutcome;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "app.durable-tasks.dispatch-enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KnowledgeMaintenanceDurabilityIntegrationTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private KnowledgeMaintenanceQueueService queueService;

    @Autowired
    private DurableTaskRunRepository taskRepository;

    @Autowired
    private KnowledgeFolderRunRepository runRepository;

    @Test
    void manualRetryCreatesLinkedRunWithoutChangingFailedHistory() {
        KnowledgeFolderRunResponse queued = queueService.enqueueFolderSync("retry-folder");
        assertThat(queueService.enqueueFolderSync("retry-folder").id()).isEqualTo(queued.id());
        long now = System.currentTimeMillis();
        DurableTaskRun claimed = taskRepository.claimNext(
                KnowledgeMaintenanceQueueService.QUEUE_NAME,
                "retry-owner",
                now,
                now + 1_000
        ).orElseThrow();
        assertThat(taskRepository.fail(claimed.id(), "retry-owner", "TEST_FAILURE", "failed")).isTrue();

        KnowledgeFolderRunResponse retried = queueService.retry(claimed.id());

        assertThat(retried.id()).isNotEqualTo(claimed.id());
        assertThat(retried.retryOfRunId()).isEqualTo(claimed.id());
        assertThat(retried.status()).isEqualTo(com.itqianchen.agentdesign.domain.enums.knowledge.KnowledgeFolderRunStatus.QUEUED);
        assertThat(taskRepository.findById(claimed.id())).get()
                .extracting(DurableTaskRun::status)
                .isEqualTo(DurableTaskStatus.FAILED);
        taskRepository.cancel(retried.id(), "test cleanup");
    }

    @Test
    void replayedDeleteKeepsCurrentAuditRunAndRemovesOlderScopeHistory() {
        String folderId = "already-deleted-folder";
        KnowledgeFolderRunResponse oldRun = queueService.enqueueFolderSync(folderId);
        long now = System.currentTimeMillis();
        DurableTaskRun oldClaim = taskRepository.claimNext(
                KnowledgeMaintenanceQueueService.QUEUE_NAME,
                "old-owner",
                now,
                now + 1_000
        ).orElseThrow();
        taskRepository.fail(oldClaim.id(), "old-owner", "OLD_FAILURE", "old");

        KnowledgeFolderRunResponse deleteRun = queueService.enqueueFolderDelete(folderId);
        DurableTaskRun deleteClaim = taskRepository.claimNext(
                KnowledgeMaintenanceQueueService.QUEUE_NAME,
                "delete-owner",
                System.currentTimeMillis() + 100,
                System.currentTimeMillis() + 1_100
        ).orElseThrow();
        DurableTaskOutcome outcome = queueService.execute(
                deleteClaim,
                new DurableTaskContext(deleteClaim.id(), deleteClaim.attempt(), (step, current, total, item, checkpoint) -> { })
        );
        taskRepository.complete(
                deleteClaim.id(),
                "delete-owner",
                outcome.status(),
                outcome.resultJson(),
                outcome.progressCurrent(),
                outcome.progressTotal()
        );

        assertThat(runRepository.findById(oldRun.id())).isEmpty();
        assertThat(runRepository.findById(deleteRun.id())).isPresent();
        assertThat(taskRepository.findById(deleteRun.id())).get()
                .extracting(DurableTaskRun::status)
                .isEqualTo(DurableTaskStatus.COMPLETED);
    }
}
