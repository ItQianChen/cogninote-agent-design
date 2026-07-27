package com.itqianchen.agentdesign.service.task;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.domain.properties.task.DurableTaskProperties;
import com.itqianchen.agentdesign.repository.task.DurableTaskRunRepository;
import jakarta.annotation.PreDestroy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.Ordered;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/** 使用 SQLite claim 和可过期租约推进本机耐久任务。 */
@Component
public class DurableTaskScheduler implements ApplicationListener<ApplicationReadyEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(DurableTaskScheduler.class);

    private final DurableTaskRunRepository repository;
    private final DurableTaskProperties properties;
    private final TaskExecutor taskExecutor;
    private final Map<String, DurableTaskHandler> handlers;
    private final List<String> queueNames;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final ConcurrentMap<String, String> inFlightClaims = new ConcurrentHashMap<>();
    private final ScheduledExecutorService coordinator = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = Thread.ofPlatform().name("durable-task-coordinator").daemon(true).unstarted(runnable);
        return thread;
    });

    public DurableTaskScheduler(
            DurableTaskRunRepository repository,
            DurableTaskProperties properties,
            TaskExecutor taskExecutor,
            List<DurableTaskHandler> handlerList
    ) {
        this.repository = repository;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        LinkedHashMap<String, DurableTaskHandler> byType = new LinkedHashMap<>();
        for (DurableTaskHandler handler : handlerList) {
            if (byType.putIfAbsent(handler.taskType(), handler) != null) {
                throw new IllegalStateException("Duplicate durable task handler: " + handler.taskType());
            }
        }
        this.handlers = Map.copyOf(byType);
        this.queueNames = handlerList.stream().map(DurableTaskHandler::queueName).distinct().toList();
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.dispatchEnabled()) {
            log.info("durable_task_dispatch_disabled");
            return;
        }
        coordinator.scheduleWithFixedDelay(
                this::pollSafely,
                0,
                properties.pollInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
        coordinator.scheduleWithFixedDelay(
                this::heartbeatSafely,
                properties.heartbeatInterval().toMillis(),
                properties.heartbeatInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    public void wake() {
        if (properties.dispatchEnabled() && !closing.get() && !coordinator.isShutdown()) {
            coordinator.execute(this::pollSafely);
        }
    }

    void pollSafely() {
        if (closing.get()) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            int recovered = repository.recoverExpired(now, List.copyOf(inFlightClaims.values()));
            if (recovered > 0) {
                log.info("durable_tasks_recovered count={}", recovered);
            }
            for (String queueName : queueNames) {
                claimAndDispatch(queueName, now);
            }
        } catch (RuntimeException ex) {
            log.error("durable_task_poll_failed", ex);
        }
    }

    private void claimAndDispatch(String queueName, long now) {
        // 每次 claim 使用独立 token；旧 worker 即使醒来，也不能写入新 claim 的状态。
        String claimToken = UUID.randomUUID().toString();
        repository.claimNext(queueName, claimToken, now, now + properties.leaseDuration().toMillis())
                .ifPresent(run -> {
                    inFlightClaims.put(run.id(), claimToken);
                    try {
                        taskExecutor.execute(() -> {
                            try {
                                if (!closing.get()) {
                                    execute(run.id(), claimToken);
                                }
                            } finally {
                                inFlightClaims.remove(run.id(), claimToken);
                                wake();
                            }
                        });
                    } catch (RuntimeException ex) {
                        inFlightClaims.remove(run.id(), claimToken);
                        if (repository.fail(run.id(), claimToken, "DISPATCH_REJECTED", "任务执行器拒绝了任务。")) {
                            DurableTaskHandler handler = handlers.get(run.taskType());
                            if (handler != null) {
                                notifyFromRepository(run.id(), "failed", handler::onFailed);
                            }
                        }
                        log.error("durable_task_dispatch_failed runId={}", run.id(), ex);
                        wake();
                    }
                });
    }

    private void execute(String runId, String claimToken) {
        DurableTaskRun run = null;
        DurableTaskHandler handler = null;
        try {
            run = repository.findById(runId).orElse(null);
            if (run == null) {
                return;
            }
            if (!claimToken.equals(run.leaseOwner())
                    || run.status() != DurableTaskStatus.RUNNING && run.status() != DurableTaskStatus.CANCELLING) {
                log.info("durable_task_stale_dispatch_skipped runId={}", runId);
                return;
            }
            handler = handlers.get(run.taskType());
            if (handler == null || !handler.supports(run.operation(), run.payloadVersion())) {
                if (repository.interrupt(
                        run.id(),
                        claimToken,
                        "UNSUPPORTED_PAYLOAD",
                        "任务类型或 payload 版本不受当前版本支持。"
                ) && handler != null) {
                    notifyFromRepository(run.id(), "failed", handler::onFailed);
                }
                return;
            }
            notifyHandler(run, "started", handler::onStarted);
            DurableTaskRun claimedRun = run;
            DurableTaskHandler activeHandler = handler;
            DurableTaskContext context = new DurableTaskContext(
                    claimedRun.id(),
                    claimedRun.attempt(),
                    (step, current, total, currentItem, checkpoint) -> updateProgress(
                            activeHandler,
                            claimedRun.id(),
                            claimToken,
                            step,
                            current,
                            total,
                            currentItem,
                            checkpoint
                    )
            );
            DurableTaskOutcome outcome = handler.execute(run, context);
            if (repository.complete(
                    run.id(),
                    claimToken,
                    outcome.status(),
                    outcome.resultJson(),
                    outcome.progressCurrent(),
                    outcome.progressTotal()
            )) {
                notifyFromRepository(run.id(), "completed", handler::onCompleted);
            }
        } catch (RuntimeException ex) {
            if (run == null) {
                log.error("durable_task_execution_state_unavailable runId={}", runId, ex);
                return;
            }
            DurableTaskFailure failure = classifyFailure(handler, run, ex);
            try {
                persistFailure(handler, run, claimToken, failure);
            } catch (RuntimeException persistenceFailure) {
                log.error("durable_task_failure_persistence_failed runId={}", run.id(), persistenceFailure);
            }
            log.warn("durable_task_failed runId={} taskType={} operation={} reason={}",
                    run.id(), run.taskType(), run.operation(), failure.message());
            log.debug("durable_task_failed_stacktrace runId={}", run.id(), ex);
        }
    }

    private DurableTaskFailure classifyFailure(DurableTaskHandler handler, DurableTaskRun run, RuntimeException ex) {
        if (handler == null) {
            return DurableTaskFailure.interrupted("UNSUPPORTED_TASK_TYPE", "任务类型不受当前版本支持。");
        }
        try {
            return handler.classifyFailure(run, ex);
        } catch (RuntimeException classificationFailure) {
            log.error("durable_task_failure_classification_failed runId={}", run.id(), classificationFailure);
            return DurableTaskFailure.failed("FAILURE_CLASSIFICATION_FAILED", "任务失败且无法分类，请查看应用日志。");
        }
    }

    private void persistFailure(
            DurableTaskHandler handler,
            DurableTaskRun run,
            String claimToken,
            DurableTaskFailure failure
    ) {
        boolean updated = failure.status()
                == com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus.INTERRUPTED
                ? repository.interrupt(run.id(), claimToken, failure.code(), failure.message())
                : repository.fail(run.id(), claimToken, failure.code(), failure.message());
        if (updated && handler != null) {
            notifyFromRepository(run.id(), "failed", handler::onFailed);
        }
    }

    private void updateProgress(
            DurableTaskHandler handler,
            String runId,
            String claimToken,
            String step,
            long current,
            long total,
            String currentItem,
            String checkpointJson
    ) {
        if (repository.updateProgress(runId, claimToken, step, current, total, currentItem, checkpointJson)) {
            notifyFromRepository(runId, "progress", handler::onProgress);
        }
    }

    void heartbeatSafely() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, String> claim : Map.copyOf(inFlightClaims).entrySet()) {
            try {
                if (!repository.renewLease(
                        claim.getKey(),
                        claim.getValue(),
                        now,
                        now + properties.leaseDuration().toMillis()
                )) {
                    inFlightClaims.remove(claim.getKey(), claim.getValue());
                }
            } catch (RuntimeException ex) {
                log.warn("durable_task_heartbeat_failed runId={}", claim.getKey(), ex);
            }
        }
    }

    private void notifyFromRepository(
            String runId,
            String callbackName,
            Consumer<DurableTaskRun> callback
    ) {
        try {
            repository.findById(runId).ifPresent(run -> notifyHandler(run, callbackName, callback));
        } catch (RuntimeException ex) {
            log.warn("durable_task_callback_snapshot_failed runId={} callback={}", runId, callbackName, ex);
        }
    }

    private void notifyHandler(
            DurableTaskRun run,
            String callbackName,
            Consumer<DurableTaskRun> callback
    ) {
        try {
            callback.accept(run);
        } catch (RuntimeException ex) {
            // 生命周期状态已经由 SQLite 持久化，通知失败不能反向改变任务结果或占住租约。
            log.warn("durable_task_callback_failed runId={} taskType={} callback={}",
                    run.id(), run.taskType(), callbackName, ex);
        }
    }

    @PreDestroy
    public void close() {
        closing.set(true);
        coordinator.shutdownNow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!inFlightClaims.isEmpty() && System.nanoTime() < deadline) {
            try {
                TimeUnit.MILLISECONDS.sleep(25);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
