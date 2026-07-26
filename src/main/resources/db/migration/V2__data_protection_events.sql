CREATE TABLE data_protection_events (
    id TEXT PRIMARY KEY,
    operation TEXT NOT NULL,
    status TEXT NOT NULL,
    artifact_name TEXT,
    artifact_sha256 TEXT,
    source_schema_version INTEGER,
    target_schema_version INTEGER,
    message TEXT,
    started_at INTEGER NOT NULL,
    completed_at INTEGER
);

CREATE INDEX idx_data_protection_events_started_at
    ON data_protection_events(started_at DESC);
