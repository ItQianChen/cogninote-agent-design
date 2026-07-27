package com.itqianchen.agentdesign.repository.task;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import com.itqianchen.agentdesign.domain.enums.task.DurableTaskStatus;
import com.itqianchen.agentdesign.mapper.task.DurableTaskRunMapper;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** 通用耐久任务状态仓储。 */
@Repository
public class DurableTaskRunRepository {

    private final DurableTaskRunMapper mapper;

    public DurableTaskRunRepository(DurableTaskRunMapper mapper) {
        this.mapper = mapper;
    }

    public void insert(DurableTaskRun run) {
        mapper.insert(run);
    }

    public Optional<DurableTaskRun> findById(String id) {
        return Optional.ofNullable(mapper.findById(id));
    }

    public Optional<DurableTaskRun> findActiveByIdempotency(String taskType, String idempotencyKey) {
        return Optional.ofNullable(mapper.findActiveByIdempotency(taskType, idempotencyKey));
    }

    public Optional<DurableTaskRun> claimNext(String queueName, String leaseOwner, long now, long leaseExpiresAt) {
        DurableTaskRun candidate = mapper.findNextClaimable(queueName, now);
        if (candidate == null || mapper.claim(candidate.id(), queueName, leaseOwner, now, leaseExpiresAt) == 0) {
            return Optional.empty();
        }
        return findById(candidate.id());
    }

    public boolean renewLease(String id, String leaseOwner, long heartbeatAt, long leaseExpiresAt) {
        return mapper.renewLease(id, leaseOwner, heartbeatAt, leaseExpiresAt) > 0;
    }

    public int recoverExpired(long now, Collection<String> excludedLeaseOwners) {
        int interrupted = mapper.interruptExpired(
                now,
                "任务中断恢复次数已耗尽，请手动重试。",
                excludedLeaseOwners
        );
        return interrupted + mapper.requeueExpired(now, excludedLeaseOwners);
    }

    public boolean updateProgress(
            String id,
            String leaseOwner,
            String step,
            long current,
            long total,
            String currentItem,
            String checkpointJson
    ) {
        return mapper.updateProgress(
                id, leaseOwner, step, current, total, currentItem, checkpointJson, System.currentTimeMillis()
        ) > 0;
    }

    public boolean complete(
            String id,
            String leaseOwner,
            DurableTaskStatus status,
            String resultJson,
            long progressCurrent,
            long progressTotal
    ) {
        return mapper.markCompleted(
                id,
                leaseOwner,
                status.name(),
                resultJson,
                progressCurrent,
                progressTotal,
                System.currentTimeMillis()
        ) > 0;
    }

    public boolean fail(String id, String leaseOwner, String errorCode, String errorMessage) {
        return mapper.markFailed(id, leaseOwner, errorCode, errorMessage, System.currentTimeMillis()) > 0;
    }

    public boolean interrupt(String id, String leaseOwner, String errorCode, String errorMessage) {
        return mapper.markInterrupted(id, leaseOwner, errorCode, errorMessage, System.currentTimeMillis()) > 0;
    }

    public boolean cancel(String id, String message) {
        return mapper.markCancelled(id, message, System.currentTimeMillis()) > 0;
    }

    public boolean deleteTerminalById(String id) {
        return mapper.deleteTerminalById(id) > 0;
    }

    public int deleteTerminalByIds(List<String> ids) {
        return ids == null || ids.isEmpty() ? 0 : mapper.deleteTerminalByIds(ids);
    }

    public int deleteTerminalByScopeExcept(String scopeType, String scopeId, String excludedId) {
        return mapper.deleteTerminalByScopeExcept(scopeType, scopeId, excludedId);
    }
}
