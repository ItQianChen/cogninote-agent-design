package com.itqianchen.agentdesign.service.task;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;

/**
 * 耐久任务领域适配器。
 *
 * <p>handler 必须按 payloadVersion 严格解码，并保证同一 payload 在中断后重新执行是幂等的。</p>
 */
public interface DurableTaskHandler {

    String taskType();

    String queueName();

    boolean supports(String operation, int payloadVersion);

    DurableTaskOutcome execute(DurableTaskRun run, DurableTaskContext context);

    default DurableTaskFailure classifyFailure(DurableTaskRun run, RuntimeException exception) {
        String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return DurableTaskFailure.failed(exception.getClass().getSimpleName(), message);
    }

    default void onStarted(DurableTaskRun run) {
    }

    default void onProgress(DurableTaskRun run) {
    }

    default void onCompleted(DurableTaskRun run) {
    }

    default void onFailed(DurableTaskRun run) {
    }
}
