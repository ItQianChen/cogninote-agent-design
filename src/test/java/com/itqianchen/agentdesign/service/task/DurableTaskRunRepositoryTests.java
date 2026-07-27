package com.itqianchen.agentdesign.service.task;

import static org.assertj.core.api.Assertions.assertThat;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import com.itqianchen.agentdesign.support.TestStorageProperties;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = "app.durable-tasks.dispatch-enabled=false")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DurableTaskRunRepositoryTests {

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void registerStorageProperties(DynamicPropertyRegistry registry) {
        TestStorageProperties.register(registry, storageRoot);
    }

    @Autowired
    private DurableTaskRunRepository repository;

    @Test
    void claimAllowsOnlyOneWorkerPerQueue() {
        repository.insert(task("claim-1", "CLAIM_QUEUE", "key-1"));
        repository.insert(task("claim-2", "CLAIM_QUEUE", "key-2"));
        long now = System.currentTimeMillis();

        assertThat(repository.claimNext("CLAIM_QUEUE", "owner-1", now, now + 1_000))
                .get().extracting(DurableTaskRun::id).isEqualTo("claim-1");
        assertThat(repository.claimNext("CLAIM_QUEUE", "owner-2", now, now + 1_000)).isEmpty();
        assertThat(repository.findById("claim-1")).get()
                .satisfies(run -> {
                    assertThat(run.status()).isEqualTo(DurableTaskStatus.RUNNING);
                    assertThat(run.attempt()).isEqualTo(1);
                });
    }

    @Test
    void expiredLeaseRetriesThreeAttemptsThenInterrupts() {
        repository.insert(task("recover-1", "RECOVER_QUEUE", "recover-key"));
        long now = System.currentTimeMillis();

        for (int attempt = 1; attempt <= 3; attempt++) {
            long claimAt = now + attempt * 100L;
            assertThat(repository.claimNext("RECOVER_QUEUE", "recover-owner", claimAt, claimAt + 10))
                    .isPresent();
            repository.recoverExpired(claimAt + 11, Set.of());
            DurableTaskRun recovered = repository.findById("recover-1").orElseThrow();
            assertThat(recovered.attempt()).isEqualTo(attempt);
            assertThat(recovered.status()).isEqualTo(
                    attempt < 3 ? DurableTaskStatus.RETRY_WAIT : DurableTaskStatus.INTERRUPTED
            );
        }
        assertThat(repository.findById("recover-1")).get()
                .extracting(DurableTaskRun::errorCode)
                .isEqualTo("RECOVERY_EXHAUSTED");
    }

    @Test
    void ordinaryFailureIsTerminalAndNotAutomaticallyRetried() {
        repository.insert(task("failed-1", "FAILURE_QUEUE", "failed-key"));
        long now = System.currentTimeMillis();
        repository.claimNext("FAILURE_QUEUE", "failure-owner", now, now + 10).orElseThrow();

        assertThat(repository.fail("failed-1", "failure-owner", "BUSINESS_FAILURE", "failed")).isTrue();
        assertThat(repository.recoverExpired(now + 100, Set.of())).isZero();
        assertThat(repository.findById("failed-1")).get()
                .extracting(DurableTaskRun::status)
                .isEqualTo(DurableTaskStatus.FAILED);
    }

    @Test
    void claimTokenFencesExpiredWorkerAndCompletionPersistsFinalProgress() {
        repository.insert(task("fenced-1", "FENCED_QUEUE", "fenced-key"));
        long now = System.currentTimeMillis();
        assertThat(repository.claimNext("FENCED_QUEUE", "claim-a", now, now + 10)).isPresent();

        assertThat(repository.recoverExpired(now + 11, Set.of("claim-a"))).isZero();
        assertThat(repository.findById("fenced-1")).get()
                .extracting(DurableTaskRun::status)
                .isEqualTo(DurableTaskStatus.RUNNING);

        assertThat(repository.recoverExpired(now + 11, Set.of())).isEqualTo(1);
        assertThat(repository.claimNext("FENCED_QUEUE", "claim-b", now + 12, now + 1_000)).isPresent();
        assertThat(repository.updateProgress("fenced-1", "claim-a", "STALE", 1, 2, null, null)).isFalse();
        assertThat(repository.complete(
                "fenced-1", "claim-a", DurableTaskStatus.COMPLETED, null, 2, 2
        )).isFalse();
        assertThat(repository.updateProgress("fenced-1", "claim-b", "WORKING", 3, 7, null, null)).isTrue();
        assertThat(repository.complete(
                "fenced-1", "claim-b", DurableTaskStatus.COMPLETED, null, 7, 7
        )).isTrue();

        assertThat(repository.findById("fenced-1")).get().satisfies(run -> {
            assertThat(run.status()).isEqualTo(DurableTaskStatus.COMPLETED);
            assertThat(run.progressCurrent()).isEqualTo(7);
            assertThat(run.progressTotal()).isEqualTo(7);
        });
    }

    private static DurableTaskRun task(String id, String queueName, String idempotencyKey) {
        long now = System.currentTimeMillis();
        return new DurableTaskRun(
                id, "TEST", queueName, "TEST", DurableTaskStatus.QUEUED, "QUEUED",
                1, "{}", null, null, true, 0, 3, idempotencyKey, null,
                null, null, null, now, 0, 1, null, null, null,
                now, null, null, null, now, now
        );
    }
}
