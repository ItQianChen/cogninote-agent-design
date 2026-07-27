CREATE TABLE durable_task_runs (
    id TEXT PRIMARY KEY,
    task_type TEXT NOT NULL,
    queue_name TEXT NOT NULL,
    operation TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN (
        'QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCELLING', 'CANCELLED',
        'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'INTERRUPTED'
    )),
    step TEXT,
    payload_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    checkpoint_json TEXT,
    result_json TEXT,
    resumable INTEGER NOT NULL DEFAULT 0 CHECK (resumable IN (0, 1)),
    attempt INTEGER NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    max_attempts INTEGER NOT NULL DEFAULT 1 CHECK (max_attempts >= 1),
    idempotency_key TEXT NOT NULL,
    retry_of_run_id TEXT,
    lease_owner TEXT,
    lease_expires_at INTEGER,
    heartbeat_at INTEGER,
    available_at INTEGER NOT NULL,
    progress_current INTEGER NOT NULL DEFAULT 0,
    progress_total INTEGER NOT NULL DEFAULT 0,
    current_item TEXT,
    error_code TEXT,
    error_message TEXT,
    queued_at INTEGER NOT NULL,
    started_at INTEGER,
    completed_at INTEGER,
    duration_ms INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (retry_of_run_id) REFERENCES durable_task_runs(id) ON DELETE SET NULL
);

INSERT INTO durable_task_runs (
    id, task_type, queue_name, operation, status, step,
    payload_version, payload_json, checkpoint_json, result_json,
    resumable, attempt, max_attempts, idempotency_key, retry_of_run_id,
    lease_owner, lease_expires_at, heartbeat_at, available_at,
    progress_current, progress_total, current_item, error_code, error_message,
    queued_at, started_at, completed_at, duration_ms, created_at, updated_at
)
SELECT
    id,
    'KNOWLEDGE_MAINTENANCE',
    'KNOWLEDGE_MUTATION',
    operation,
    CASE WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING') THEN 'INTERRUPTED' ELSE status END,
    CASE WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING') THEN 'INTERRUPTED' ELSE phase END,
    0,
    '{}',
    NULL,
    NULL,
    0,
    CASE WHEN started_at IS NULL THEN 0 ELSE 1 END,
    1,
    'legacy:' || id,
    NULL,
    NULL,
    NULL,
    NULL,
    COALESCE(queued_at, created_at),
    progress_current,
    progress_total,
    current_item,
    CASE WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING') THEN 'LEGACY_PAYLOAD_UNAVAILABLE' ELSE error_code END,
    CASE
        WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING')
            THEN '升级前任务缺少可验证参数，已停止自动恢复。'
        ELSE error_message
    END,
    COALESCE(queued_at, created_at),
    started_at,
    CASE
        WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING')
            THEN CAST(strftime('%s', 'now') AS INTEGER) * 1000
        ELSE completed_at
    END,
    CASE
        WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING') AND started_at IS NOT NULL
            THEN MAX(0, CAST(strftime('%s', 'now') AS INTEGER) * 1000 - started_at)
        ELSE duration_ms
    END,
    created_at,
    CASE
        WHEN status IN ('QUEUED', 'RUNNING', 'CANCELLING')
            THEN CAST(strftime('%s', 'now') AS INTEGER) * 1000
        ELSE updated_at
    END
FROM knowledge_folder_runs;

CREATE TABLE knowledge_folder_runs_v3 (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    parsed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    indexed_document_count INTEGER NOT NULL DEFAULT 0,
    indexed_chunk_count INTEGER NOT NULL DEFAULT 0,
    failed_document_count INTEGER NOT NULL DEFAULT 0,
    failures_json TEXT,
    error_stage TEXT,
    error_detail TEXT,
    FOREIGN KEY (id) REFERENCES durable_task_runs(id) ON DELETE CASCADE
);

INSERT INTO knowledge_folder_runs_v3 (
    id, scope_type, scope_id, scanned_count, parsed_count, skipped_count, failed_count,
    indexed_document_count, indexed_chunk_count, failed_document_count,
    failures_json, error_stage, error_detail
)
SELECT
    id, scope_type, scope_id, scanned_count, parsed_count, skipped_count, failed_count,
    indexed_document_count, indexed_chunk_count, failed_document_count,
    failures_json, error_stage, error_detail
FROM knowledge_folder_runs;

DROP TABLE knowledge_folder_runs;
ALTER TABLE knowledge_folder_runs_v3 RENAME TO knowledge_folder_runs;

CREATE INDEX idx_durable_tasks_queue
    ON durable_task_runs(queue_name, status, available_at, queued_at);
CREATE INDEX idx_durable_tasks_lease
    ON durable_task_runs(status, lease_expires_at);
CREATE INDEX idx_durable_tasks_operation
    ON durable_task_runs(task_type, operation, created_at DESC);
CREATE UNIQUE INDEX idx_durable_tasks_active_idempotency
    ON durable_task_runs(task_type, idempotency_key)
    WHERE status IN ('QUEUED', 'RUNNING', 'RETRY_WAIT', 'CANCELLING');
CREATE INDEX idx_kf_runs_scope
    ON knowledge_folder_runs(scope_type, scope_id, id);
