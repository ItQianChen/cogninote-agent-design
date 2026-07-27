package com.itqianchen.agentdesign.domain.enums.knowledge;

/**
 * 知识库维护任务状态。
 *
 * <p>状态由 durable_task_runs 持久化，同时驱动前端队列展示和按钮禁用规则；
 * 新增或改名必须同步 SQLite 迁移、Mapper、前端状态映射和测试。</p>
 */
public enum KnowledgeFolderRunStatus {
    /** 等待后台维护 worker 执行。 */
    QUEUED,

    /** 操作正在执行，前端应禁用冲突操作并展示进度。 */
    RUNNING,

    /** 上次进程中断，等待重新获取租约后恢复。 */
    RETRY_WAIT,

    /** 已收到取消请求，任务会在下一个安全检查点停止。 */
    CANCELLING,

    /** 任务在执行前或执行中被用户取消。 */
    CANCELLED,

    /** 操作完成且没有报告失败项。 */
    COMPLETED,

    /** 操作完成，但存在解析失败或索引失败等需要用户关注的问题。 */
    COMPLETED_WITH_WARNINGS,

    /** 操作整体失败。 */
    FAILED,

    /** 任务无法自动恢复，需要用户检查后手动重试。 */
    INTERRUPTED
}
