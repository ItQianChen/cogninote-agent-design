CREATE TABLE knowledge_folders (
    id TEXT PRIMARY KEY,
    folder_path TEXT NOT NULL,
    display_name TEXT NOT NULL,
    recursive INTEGER NOT NULL DEFAULT 1,
    enabled INTEGER NOT NULL DEFAULT 1,
    document_count INTEGER NOT NULL DEFAULT 0,
    last_ingested_at INTEGER,
    last_indexed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE documents (
    id TEXT PRIMARY KEY,
    knowledge_folder_id TEXT,
    source_path TEXT NOT NULL,
    file_name TEXT NOT NULL,
    file_type TEXT NOT NULL,
    file_size INTEGER,
    last_modified INTEGER,
    content_hash TEXT,
    status TEXT NOT NULL,
    indexed_at INTEGER,
    created_at INTEGER,
    updated_at INTEGER,
    last_failure_stage TEXT,
    last_failure_code TEXT,
    last_failure_message TEXT,
    last_failure_detail TEXT,
    last_failure_context_json TEXT,
    last_failed_at INTEGER
);

CREATE TABLE document_ocr_checkpoints (
    document_id TEXT PRIMARY KEY,
    source_content_hash TEXT NOT NULL,
    ocr_signature TEXT NOT NULL,
    total_pages INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE document_ocr_checkpoint_pages (
    document_id TEXT NOT NULL,
    page_number INTEGER NOT NULL,
    page_text TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (document_id, page_number),
    FOREIGN KEY (document_id) REFERENCES document_ocr_checkpoints(document_id) ON DELETE CASCADE
);

CREATE TABLE chunks (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    content TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    page_number INTEGER,
    heading TEXT,
    token_count INTEGER,
    created_at INTEGER,
    FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE model_configs (
    id TEXT PRIMARY KEY,
    role TEXT NOT NULL,
    provider TEXT NOT NULL,
    display_name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT,
    model_name TEXT NOT NULL,
    embedding_dimensions INTEGER,
    embedding_requests_per_minute INTEGER,
    embedding_tokens_per_minute INTEGER,
    embedding_batch_size INTEGER,
    temperature REAL,
    default_top_k INTEGER,
    context_window_tokens INTEGER,
    is_active INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE chat_sessions (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    summary TEXT,
    summary_message_sequence INTEGER NOT NULL DEFAULT 0,
    use_knowledge_base INTEGER NOT NULL DEFAULT 1,
    retrieval_mode TEXT NOT NULL DEFAULT 'HYBRID',
    top_k INTEGER NOT NULL DEFAULT 8,
    deleted INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE chat_messages (
    id TEXT PRIMARY KEY,
    conversation_id TEXT NOT NULL,
    message_sequence INTEGER NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    status TEXT NOT NULL,
    request_id TEXT,
    agent_type TEXT,
    retrieval_mode TEXT,
    sources_json TEXT,
    references_json TEXT,
    token_estimate INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (conversation_id) REFERENCES chat_sessions(id) ON DELETE CASCADE
);

CREATE TABLE app_settings (
    setting_key TEXT PRIMARY KEY,
    setting_value TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE knowledge_graph_runs (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    status TEXT NOT NULL,
    model_config_id TEXT,
    prompt_version TEXT NOT NULL,
    total_chunk_count INTEGER NOT NULL DEFAULT 0,
    processed_chunk_count INTEGER NOT NULL DEFAULT 0,
    skipped_chunk_count INTEGER NOT NULL DEFAULT 0,
    extracted_node_count INTEGER NOT NULL DEFAULT 0,
    extracted_edge_count INTEGER NOT NULL DEFAULT 0,
    failed_chunk_count INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at INTEGER,
    completed_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE knowledge_folder_runs (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    operation TEXT NOT NULL,
    status TEXT NOT NULL,
    scanned_count INTEGER NOT NULL DEFAULT 0,
    parsed_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    indexed_document_count INTEGER NOT NULL DEFAULT 0,
    indexed_chunk_count INTEGER NOT NULL DEFAULT 0,
    failed_document_count INTEGER NOT NULL DEFAULT 0,
    failures_json TEXT,
    phase TEXT,
    progress_current INTEGER NOT NULL DEFAULT 0,
    progress_total INTEGER NOT NULL DEFAULT 0,
    current_item TEXT,
    queued_at INTEGER,
    started_at INTEGER,
    completed_at INTEGER,
    duration_ms INTEGER,
    error_message TEXT,
    error_stage TEXT,
    error_code TEXT,
    error_detail TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE knowledge_graph_chunk_extractions (
    chunk_id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    content_hash TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    model_config_id TEXT,
    status TEXT NOT NULL,
    extraction_json TEXT,
    error_message TEXT,
    extracted_at INTEGER
);

CREATE TABLE knowledge_graph_nodes (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    canonical_name TEXT NOT NULL,
    display_name TEXT NOT NULL,
    node_type TEXT NOT NULL,
    description TEXT,
    confidence REAL NOT NULL DEFAULT 0,
    mention_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE knowledge_graph_edges (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    source_node_id TEXT NOT NULL,
    target_node_id TEXT NOT NULL,
    relation_type TEXT NOT NULL,
    display_label TEXT NOT NULL DEFAULT '相关',
    description TEXT,
    confidence REAL NOT NULL DEFAULT 0,
    mention_count INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (source_node_id) REFERENCES knowledge_graph_nodes(id) ON DELETE CASCADE,
    FOREIGN KEY (target_node_id) REFERENCES knowledge_graph_nodes(id) ON DELETE CASCADE
);

CREATE TABLE knowledge_graph_evidence (
    id TEXT PRIMARY KEY,
    run_id TEXT NOT NULL,
    node_id TEXT,
    edge_id TEXT,
    document_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    quote TEXT NOT NULL,
    confidence REAL NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL
);

CREATE TABLE knowledge_graph_views (
    id TEXT PRIMARY KEY,
    scope_type TEXT NOT NULL,
    scope_id TEXT,
    view_type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    generated_from_run_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_knowledge_folders_path ON knowledge_folders(folder_path);
CREATE INDEX idx_knowledge_folders_enabled ON knowledge_folders(enabled);
CREATE INDEX idx_documents_knowledge_folder_id ON documents(knowledge_folder_id);
CREATE INDEX idx_documents_updated_at ON documents(updated_at DESC);
CREATE INDEX idx_chunks_document_id ON chunks(document_id);
CREATE INDEX idx_model_configs_role ON model_configs(role);
CREATE INDEX idx_model_configs_role_active ON model_configs(role, is_active);
CREATE INDEX idx_chat_sessions_updated_at ON chat_sessions(deleted, updated_at DESC);
CREATE UNIQUE INDEX idx_chat_messages_sequence ON chat_messages(conversation_id, message_sequence);
CREATE INDEX idx_chat_messages_conversation_id ON chat_messages(conversation_id, created_at);
CREATE UNIQUE INDEX idx_kg_nodes_scope_canonical
    ON knowledge_graph_nodes(scope_type, scope_id, canonical_name, node_type);
CREATE INDEX idx_kg_edges_scope ON knowledge_graph_edges(scope_type, scope_id);
CREATE UNIQUE INDEX idx_kg_edges_scope_triple
    ON knowledge_graph_edges(scope_type, scope_id, source_node_id, target_node_id, relation_type, display_label);
CREATE INDEX idx_kg_evidence_node ON knowledge_graph_evidence(node_id);
CREATE INDEX idx_kg_evidence_edge ON knowledge_graph_evidence(edge_id);
CREATE INDEX idx_kg_evidence_chunk ON knowledge_graph_evidence(chunk_id);
CREATE INDEX idx_kg_runs_scope_status ON knowledge_graph_runs(scope_type, scope_id, status);
CREATE INDEX idx_kf_runs_scope ON knowledge_folder_runs(scope_type, scope_id, created_at DESC);
CREATE INDEX idx_kf_runs_operation ON knowledge_folder_runs(operation, created_at DESC);
CREATE INDEX idx_kf_runs_status ON knowledge_folder_runs(status, created_at);
CREATE INDEX idx_kg_views_scope ON knowledge_graph_views(scope_type, scope_id, view_type);

INSERT INTO model_configs (
    id, role, provider, display_name, base_url, api_key, model_name,
    embedding_dimensions, embedding_requests_per_minute, embedding_tokens_per_minute, embedding_batch_size,
    temperature, default_top_k, context_window_tokens, is_active, created_at, updated_at
) VALUES
    ('active-chat', 'CHAT', 'DASHSCOPE', 'DashScope Chat', 'https://dashscope.aliyuncs.com/api/v1', '',
     'qwen-plus', NULL, NULL, NULL, NULL, 0.7, 8, 128000, 1,
     CAST(strftime('%s', 'now') AS INTEGER) * 1000, CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('active-embedding', 'EMBEDDING', 'DASHSCOPE', 'DashScope Embedding', 'https://dashscope.aliyuncs.com/api/v1', '',
     'text-embedding-v4', 1024, 300, 300000, 16, NULL, NULL, NULL, 1,
     CAST(strftime('%s', 'now') AS INTEGER) * 1000, CAST(strftime('%s', 'now') AS INTEGER) * 1000),
    ('active-vision', 'VISION', 'DASHSCOPE', 'DashScope Vision', 'https://dashscope.aliyuncs.com/api/v1', '',
     'qwen3-vl-plus', NULL, NULL, NULL, NULL, 0.0, NULL, NULL, 1,
     CAST(strftime('%s', 'now') AS INTEGER) * 1000, CAST(strftime('%s', 'now') AS INTEGER) * 1000);
