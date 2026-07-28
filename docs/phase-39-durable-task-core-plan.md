# 第 39 阶段：耐久任务核心与知识维护队列恢复

## 目标

第 39 阶段把知识维护任务的参数、生命周期、租约、进度和恢复次数统一收敛到 SQLite。进程退出后不再依赖 JVM 内存猜测任务参数；排队任务可以继续执行，租约过期任务可以有限恢复，备份中的旧活动任务不会被自动重放。

本阶段只迁移知识维护队列。知识图谱和 OCR 的运行模型保持不变，分别留给 Phase 39.1 和 Phase 39.2。

## 数据模型

- 新增 Flyway `V3__durable_task_runs.sql`，V1/V2 保持不可变。
- `durable_task_runs` 是通用生命周期的唯一事实源，保存 task type、queue、operation、状态、版本化 payload、不透明 checkpoint、结果、幂等键、重试关联、租约、进度、错误和时间字段。
- 状态固定为 `QUEUED`、`RUNNING`、`RETRY_WAIT`、`CANCELLING`、`CANCELLED`、`COMPLETED`、`COMPLETED_WITH_WARNINGS`、`FAILED`、`INTERRUPTED`。
- `knowledge_folder_runs` 重建为领域扩展表，只保存 scope、维护统计、失败列表和领域错误，通过相同 ID 关联通用任务。
- V2 终态历史迁移到通用表；缺少可靠 payload 的 V2 活动任务转为 `INTERRUPTED/LEGACY_PAYLOAD_UNAVAILABLE`，不会猜测参数执行。
- 活动任务幂等键使用局部唯一索引；队列 claim、租约扫描、operation 和 scope 均有对应索引。
- schema 版本和备份包表白名单升级到 3，V3 备份必须包含 `durable_task_runs`。

## 调度与恢复

- `DurableTaskHandler` 按 task type 注册，并按 operation、payload version 判断支持范围；`DurableTaskContext` 只暴露进度和不透明 checkpoint 写入边界。
- `DurableTaskScheduler` 默认每 2 秒轮询、每 10 秒续租，租约为 60 秒。每次 claim 生成独立 fencing token，所有进度和终态写入均校验该 token；本 JVM 的在途 token 不参与过期回收。数据库原子 claim 会被队列中任意 `RUNNING/CANCELLING` 行阻止，过期行必须先完成恢复转换，确保 `KNOWLEDGE_MUTATION` 最多一个 worker。
- 新任务第一次 claim 时 `attempt=1`。进程中断导致租约过期后进入 `RETRY_WAIT`，重新 claim 会递增 attempt；第三次执行再次中断后进入 `INTERRUPTED/RECOVERY_EXHAUSTED`。
- handler 抛出的普通解析、索引、路径或业务异常直接进入 `FAILED`，不会自动重试。
- payload 缺失、损坏、版本不支持或 operation 不一致时进入 `INTERRUPTED`。
- checkpoint 保持领域不透明；当前阶段的 `currentItem` 只是可观察进度，不是恢复游标。
- 恢复备份完成迁移和校验后，库内所有活动耐久任务统一转为 `INTERRUPTED/RESTORE_BOUNDARY`。用户可显式重试受支持的任务，但系统不会自动重放备份时间点的旧副作用。

## 知识维护接入

- `MaintenanceTaskPayloadV1` 持久化 `scopeType/scopeId/operation/folderPath/recursive/enabled`，使用严格 JSON 解码；未知字段和不合法组合会被拒绝。
- IMPORT、SYNC、REPARSE、REPAIR_INDEX、REBUILD_INDEX、ENABLE、DISABLE、DELETE 通过同一 handler 执行。OCR 页级检查点仍由既有摄取流程自然复用。
- 活动 payload 的 SHA-256 幂等键相同时返回已有 run；旧 run 进入终态后可再次创建。
- DELETE 可安全重放：目标已不存在视为完成，只删除同 scope 的旧终态历史，当前 DELETE run 保留为审计记录。
- 现有维护 API 和 SSE 事件名保持兼容；新增 `POST /api/knowledge-maintenance/runs/{runId}/retry`。
- retry 只接受 `FAILED` 或 `INTERRUPTED` 且 payload 仍受支持的任务，创建新 run 并设置 `retryOfRunId`，不改写旧历史。
- `QUEUED` 和 `RETRY_WAIT` 可以取消；`RUNNING` 仍执行到安全完成点。
- 响应新增 `attempt/maxAttempts/resumable/retryOfRunId/nextAttemptAt`。前端在现有队列和维护历史中显示恢复等待、中断、执行次数和手动重试，不新增通用任务中心。
- SSE 异常时先用 HTTP 快照校正状态，再延迟重连当前任务；旧响应缺少新增字段时仍可展示。

## 验证

自动化覆盖：

- V2 到 V3 的终态历史迁移、活动任务中断和领域表重建。
- payload 严格解码、活动幂等、原子 claim、单 worker、租约恢复和第三次中断。
- 普通失败不自动重试、等待态取消、手动 retry 新建关联 run。
- DELETE 重放和当前审计 run 保留。
- 恢复库活动任务隔离。
- 前端 `RETRY_WAIT/INTERRUPTED` 状态、取消、手动重试、终态 SSE 和旧响应兼容。

耐久队列 smoke 使用同一临时数据目录执行两次启动：

```powershell
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'D:\CodeApps\Java-JDK\jdk-25.0.2'
pwsh -NoProfile -File scripts/smoke-durable-maintenance.ps1
```

第一次启动通过 `COGNINOTE_DURABLE_TASK_DISPATCH_ENABLED=false` 只入队；第二次启用 dispatcher，断言原 run 完成且哨兵文档可被关键词检索。

完整门禁包括 Maven 全量测试和打包、前端测试与生产构建、bundle budget、备份恢复 smoke、耐久队列 smoke 和 `git diff --check`。后端多轮测试保留为排查偶发失败时的本地诊断手段，不作为固定发布门禁。

## 非目标与后续

- Phase 39.1：把知识图谱运行接入耐久任务核心。
- Phase 39.2：把 OCR 检查点暴露为可观察子任务。
- 本阶段不迁移恢复后的索引重建、聊天流任务，不引入消息队列、Redis、Quartz、云同步或跨设备调度。
