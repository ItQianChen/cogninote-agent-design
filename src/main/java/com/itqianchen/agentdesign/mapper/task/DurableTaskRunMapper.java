package com.itqianchen.agentdesign.mapper.task;

import com.itqianchen.agentdesign.domain.entity.task.DurableTaskRun;
import java.util.Collection;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 通用耐久任务的 MyBatis SQL 边界。 */
public interface DurableTaskRunMapper {

    void insert(DurableTaskRun run);

    DurableTaskRun findById(@Param("id") String id);

    DurableTaskRun findActiveByIdempotency(
            @Param("taskType") String taskType,
            @Param("idempotencyKey") String idempotencyKey
    );

    DurableTaskRun findNextClaimable(@Param("queueName") String queueName, @Param("now") long now);

    int claim(
            @Param("id") String id,
            @Param("queueName") String queueName,
            @Param("leaseOwner") String leaseOwner,
            @Param("now") long now,
            @Param("leaseExpiresAt") long leaseExpiresAt
    );

    int renewLease(
            @Param("id") String id,
            @Param("leaseOwner") String leaseOwner,
            @Param("heartbeatAt") long heartbeatAt,
            @Param("leaseExpiresAt") long leaseExpiresAt
    );

    int requeueExpired(
            @Param("now") long now,
            @Param("excludedLeaseOwners") Collection<String> excludedLeaseOwners
    );

    int interruptExpired(
            @Param("now") long now,
            @Param("message") String message,
            @Param("excludedLeaseOwners") Collection<String> excludedLeaseOwners
    );

    int updateProgress(
            @Param("id") String id,
            @Param("leaseOwner") String leaseOwner,
            @Param("step") String step,
            @Param("progressCurrent") long progressCurrent,
            @Param("progressTotal") long progressTotal,
            @Param("currentItem") String currentItem,
            @Param("checkpointJson") String checkpointJson,
            @Param("updatedAt") long updatedAt
    );

    int markCompleted(
            @Param("id") String id,
            @Param("leaseOwner") String leaseOwner,
            @Param("status") String status,
            @Param("resultJson") String resultJson,
            @Param("progressCurrent") long progressCurrent,
            @Param("progressTotal") long progressTotal,
            @Param("completedAt") long completedAt
    );

    int markFailed(
            @Param("id") String id,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt
    );

    int markInterrupted(
            @Param("id") String id,
            @Param("leaseOwner") String leaseOwner,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("completedAt") long completedAt
    );

    int markCancelled(@Param("id") String id, @Param("message") String message, @Param("completedAt") long completedAt);

    int deleteTerminalById(@Param("id") String id);

    int deleteTerminalByIds(@Param("ids") List<String> ids);

    int deleteTerminalByScopeExcept(
            @Param("scopeType") String scopeType,
            @Param("scopeId") String scopeId,
            @Param("excludedId") String excludedId
    );
}
