package com.itqianchen.agentdesign.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.domain.properties.task.DurableTaskProperties;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;

class DurableTaskSchedulerTests {

    private final DurableTaskRunRepository repository = mock(DurableTaskRunRepository.class);
    private final DurableTaskHandler handler = mock(DurableTaskHandler.class);
    private final DurableTaskProperties properties = new DurableTaskProperties(
            false,
            Duration.ofSeconds(2),
            Duration.ofSeconds(10),
            Duration.ofSeconds(60)
    );

    @Test
    void localInFlightClaimIsExcludedFromRecoveryAndRenewedByItsOwnToken() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        AtomicReference<DurableTaskRun> claimedRun = new AtomicReference<>();
        AtomicBoolean firstClaim = new AtomicBoolean(true);
        TaskExecutor executor = submitted::set;
        configureHandler();
        when(repository.recoverExpired(anyLong(), anyCollection())).thenReturn(0);
        when(repository.claimNext(eq("TEST_QUEUE"), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    if (!firstClaim.getAndSet(false)) {
                        return Optional.empty();
                    }
                    DurableTaskRun run = runningTask(invocation.getArgument(1));
                    claimedRun.set(run);
                    return Optional.of(run);
                });
        when(repository.findById("run-1")).thenAnswer(invocation -> Optional.of(claimedRun.get()));
        when(handler.execute(any(), any())).thenReturn(new DurableTaskOutcome(DurableTaskStatus.COMPLETED, null));
        when(repository.complete(eq("run-1"), anyString(), eq(DurableTaskStatus.COMPLETED),
                eq(null), eq(1L), eq(1L))).thenReturn(true);
        when(repository.renewLease(eq("run-1"), anyString(), anyLong(), anyLong())).thenReturn(true);
        DurableTaskScheduler scheduler = new DurableTaskScheduler(repository, properties, executor, List.of(handler));

        scheduler.pollSafely();
        assertThat(submitted.get()).isNotNull();
        String claimToken = claimedRun.get().leaseOwner();

        clearInvocations(repository);
        scheduler.pollSafely();
        ArgumentCaptor<Collection<String>> exclusions = collectionCaptor();
        verify(repository).recoverExpired(anyLong(), exclusions.capture());
        assertThat(exclusions.getValue()).containsExactly(claimToken);

        scheduler.heartbeatSafely();
        verify(repository).renewLease(eq("run-1"), eq(claimToken), anyLong(), anyLong());

        submitted.get().run();
        clearInvocations(repository);
        scheduler.heartbeatSafely();
        verify(repository, never()).renewLease(anyString(), anyString(), anyLong(), anyLong());
        scheduler.close();
    }

    @Test
    void startedCallbackFailureCannotLeaveAHeartbeatingOrphan() {
        configureHandler();
        AtomicReference<DurableTaskRun> claimedRun = new AtomicReference<>();
        when(repository.recoverExpired(anyLong(), anyCollection())).thenReturn(0);
        when(repository.claimNext(eq("TEST_QUEUE"), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    DurableTaskRun run = runningTask(invocation.getArgument(1));
                    claimedRun.set(run);
                    return Optional.of(run);
                });
        when(repository.findById("run-1")).thenAnswer(invocation -> Optional.of(claimedRun.get()));
        doThrow(new IllegalStateException("SSE unavailable")).when(handler).onStarted(any());
        when(handler.execute(any(), any())).thenReturn(new DurableTaskOutcome(DurableTaskStatus.COMPLETED, null));
        when(repository.complete(eq("run-1"), anyString(), eq(DurableTaskStatus.COMPLETED),
                eq(null), eq(1L), eq(1L))).thenReturn(true);
        DurableTaskScheduler scheduler = new DurableTaskScheduler(repository, properties, Runnable::run, List.of(handler));

        scheduler.pollSafely();

        verify(handler).execute(eq(claimedRun.get()), any(DurableTaskContext.class));
        verify(repository).complete(
                "run-1",
                claimedRun.get().leaseOwner(),
                DurableTaskStatus.COMPLETED,
                null,
                1,
                1
        );
        clearInvocations(repository);
        scheduler.heartbeatSafely();
        verify(repository, never()).renewLease(anyString(), anyString(), anyLong(), anyLong());
        scheduler.close();
    }

    @Test
    void failureClassificationFailureUsesFencedFallbackAndReleasesClaim() {
        configureHandler();
        AtomicReference<DurableTaskRun> claimedRun = new AtomicReference<>();
        when(repository.recoverExpired(anyLong(), anyCollection())).thenReturn(0);
        when(repository.claimNext(eq("TEST_QUEUE"), anyString(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    DurableTaskRun run = runningTask(invocation.getArgument(1));
                    claimedRun.set(run);
                    return Optional.of(run);
                });
        when(repository.findById("run-1")).thenAnswer(invocation -> Optional.of(claimedRun.get()));
        when(handler.execute(any(), any())).thenThrow(new IllegalStateException("execution failed"));
        when(handler.classifyFailure(any(), any())).thenThrow(new IllegalArgumentException("classifier failed"));
        when(repository.fail(
                eq("run-1"),
                anyString(),
                eq("FAILURE_CLASSIFICATION_FAILED"),
                eq("任务失败且无法分类，请查看应用日志。")
        )).thenReturn(true);
        DurableTaskScheduler scheduler = new DurableTaskScheduler(repository, properties, Runnable::run, List.of(handler));

        scheduler.pollSafely();

        verify(repository).fail(
                "run-1",
                claimedRun.get().leaseOwner(),
                "FAILURE_CLASSIFICATION_FAILED",
                "任务失败且无法分类，请查看应用日志。"
        );
        clearInvocations(repository);
        scheduler.heartbeatSafely();
        verify(repository, never()).renewLease(anyString(), anyString(), anyLong(), anyLong());
        scheduler.close();
    }

    private void configureHandler() {
        when(handler.taskType()).thenReturn("TEST");
        when(handler.queueName()).thenReturn("TEST_QUEUE");
        when(handler.supports("TEST", 1)).thenReturn(true);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Collection<String>> collectionCaptor() {
        return ArgumentCaptor.forClass((Class) Collection.class);
    }

    private static DurableTaskRun runningTask(String claimToken) {
        long now = System.currentTimeMillis();
        return new DurableTaskRun(
                "run-1", "TEST", "TEST_QUEUE", "TEST", DurableTaskStatus.RUNNING, "TEST",
                1, "{}", null, null, true, 1, 3, "key-1", null,
                claimToken, now + 60_000, now, now, 0, 1, null, null, null,
                now, now, null, null, now, now
        );
    }
}
