-- Current schema: one CREATE TABLE per table. Existing data upgrades live in upgrade.sql.
USE mindrails;

CREATE TABLE IF NOT EXISTS chat_memory (
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    messages JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (memory_id)
);

CREATE TABLE IF NOT EXISTS providers (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(255) NOT NULL, integration_mode VARCHAR(64) NOT NULL, streaming BOOLEAN NOT NULL,
    base_url VARCHAR(2048) NULL, api_key TEXT NULL, model_name VARCHAR(255) NOT NULL,
    reasoning BOOLEAN NULL, thinking_level VARCHAR(32) NULL, model_cost DECIMAL(20, 8) NULL,
    context_window INT NULL, max_tokens INT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    default_marker TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY providers_name (name),
    UNIQUE KEY providers_one_default (default_marker)
);

-- A singleton row serializes catalog/default changes, including an initially empty catalog.
CREATE TABLE IF NOT EXISTS image_generation_provider_catalog (
    id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT image_provider_catalog_singleton CHECK (id=1)
);
INSERT IGNORE INTO image_generation_provider_catalog (id) VALUES (1);

CREATE TABLE IF NOT EXISTS image_generation_providers (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    provider_name VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    url VARCHAR(2048) NOT NULL, api_key TEXT NOT NULL, model VARCHAR(255) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE, enabled BOOLEAN NOT NULL DEFAULT TRUE,
    size VARCHAR(32) NOT NULL DEFAULT '1024x1024', prompt_extend BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_millis INT NOT NULL DEFAULT 180000,
    tested BOOLEAN NOT NULL DEFAULT FALSE, test_success BOOLEAN NOT NULL DEFAULT FALSE,
    tested_at TIMESTAMP(6) NULL, test_latency_ms BIGINT NULL, test_message VARCHAR(512) NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    default_marker TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id), UNIQUE KEY image_providers_name (name),
    UNIQUE KEY image_providers_one_default (default_marker),
    CONSTRAINT image_providers_vendor CHECK (provider_name IN ('qwen','openai'))
);

CREATE TABLE IF NOT EXISTS image_generation_provider_tests (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_version BIGINT UNSIGNED NOT NULL, connected BOOLEAN NOT NULL,
    latency_ms BIGINT NOT NULL, message VARCHAR(512) NOT NULL, tested_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id), KEY image_provider_tests_time (provider_id,tested_at),
    CONSTRAINT image_provider_test_provider FOREIGN KEY (provider_id) REFERENCES image_generation_providers(id) ON DELETE CASCADE
);

-- Serialize catalog writes even when the provider table is empty.
CREATE TABLE IF NOT EXISTS web_search_provider_catalog (
    id TINYINT NOT NULL PRIMARY KEY,
    CONSTRAINT web_search_catalog_singleton CHECK (id=1)
);
INSERT IGNORE INTO web_search_provider_catalog (id) VALUES (1);

CREATE TABLE IF NOT EXISTS web_search_providers (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    endpoint VARCHAR(2048) NOT NULL,
    api_key TEXT NOT NULL,
    model_name VARCHAR(255) NULL,
    options JSON NOT NULL DEFAULT (JSON_OBJECT()),
    timeout_millis INT NOT NULL DEFAULT 15000,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    active_marker TINYINT GENERATED ALWAYS AS (CASE WHEN active THEN 1 ELSE NULL END) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY web_search_providers_name (name),
    UNIQUE KEY web_search_providers_one_active (active_marker),
    CONSTRAINT web_search_type CHECK (type IN ('openai_responses','deepseek_search','bocha_web_search')),
    CONSTRAINT web_search_model CHECK (
        (type='openai_responses' AND model_name IS NOT NULL AND CHAR_LENGTH(TRIM(model_name))>0)
        OR (type<>'openai_responses' AND model_name IS NULL)
    ),
    CONSTRAINT web_search_options CHECK (JSON_TYPE(options)='OBJECT'),
    CONSTRAINT web_search_timeout CHECK (timeout_millis BETWEEN 1 AND 120000),
    CONSTRAINT web_search_active CHECK (active IN (0,1))
);

CREATE TABLE IF NOT EXISTS agents (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    -- Legacy discriminator for excluding retired SmartApp agents.
    special_key VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL,
    name VARCHAR(255) NOT NULL, description TEXT NULL, goal TEXT NULL, system_message MEDIUMTEXT NULL,
    skill_ids JSON NOT NULL, connection_ids JSON NOT NULL,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    knowledge_base_ids JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY agents_owner_created (owner_id, created_at, id)
);

CREATE TABLE IF NOT EXISTS sessions (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    system_message MEDIUMTEXT NULL,
    -- Legacy discriminator for excluding retired SmartApp sessions.
    smart_app JSON NULL, skill_ids JSON NOT NULL,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY sessions_memory (memory_id),
    KEY sessions_agent_created (parent_agent_id, created_at, id),
    KEY sessions_provider (provider_id),
    CONSTRAINT sessions_parent FOREIGN KEY (parent_agent_id) REFERENCES agents (id) ON DELETE RESTRICT,
    KEY sessions_user_time (owner_id,created_at,id),
    KEY sessions_owner_updated (owner_id,updated_at DESC,id ASC)
);

CREATE TABLE IF NOT EXISTS knowledge_binding_backup_20261017 (
    kind VARCHAR(16) NOT NULL, id CHAR(36) NOT NULL, knowledge_base_id CHAR(36) NULL,
    PRIMARY KEY (kind, id)
);

CREATE TABLE IF NOT EXISTS tasks (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    parent_agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(160) NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instructions MEDIUMTEXT NOT NULL, skill_ids JSON NOT NULL, cronjob VARCHAR(255) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (id), KEY tasks_agent_created (parent_agent_id,created_at,id),
    CONSTRAINT tasks_parent FOREIGN KEY (parent_agent_id) REFERENCES agents(id) ON DELETE RESTRICT,
    KEY tasks_user_time (owner_id,created_at,id)
);

CREATE TABLE IF NOT EXISTS task_executions (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    execution JSON NOT NULL,
    PRIMARY KEY (id), KEY task_executions_task_time (agent_id,task_id,created_at,id),
    KEY task_executions_user_time (owner_id,created_at,id)
);

CREATE TABLE IF NOT EXISTS task_execution_records (
    execution_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sequence BIGINT UNSIGNED NOT NULL, record JSON NOT NULL,
    PRIMARY KEY (execution_id,sequence),
    CONSTRAINT task_execution_records_parent FOREIGN KEY (execution_id) REFERENCES task_executions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS history (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    transport VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, messages JSON NOT NULL,
    metadata JSON NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    schema_version INT NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    INDEX idx_history_session_time (session_id, started_at, id),
    INDEX idx_history_agent_time (agent_id, started_at, id),
    INDEX idx_history_owner_time (owner_id, started_at, id),
    CONSTRAINT chk_history_transport CHECK (transport IN ('SYNC', 'STREAM')),
    CONSTRAINT chk_history_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    INDEX idx_history_time (started_at,id),
    INDEX history_task_time (task_id,started_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS metrics_runs (
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    instance_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    agent_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    task_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    -- Retain publication metadata on previously recorded runs.
    app_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    app_revision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    app_release_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    app_mode VARCHAR(16) NULL,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    memory_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_name VARCHAR(255) NULL, model_name VARCHAR(255) NULL,
    session_version BIGINT UNSIGNED NOT NULL, transport VARCHAR(255) NULL,
    configured_skill_ids JSON NULL, status VARCHAR(255) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NULL,
    last_observed_at TIMESTAMP(6) NOT NULL,
    duration_ms BIGINT UNSIGNED NULL, first_response_ms BIGINT UNSIGNED NULL,
    terminal_reason VARCHAR(255) NULL, error_code VARCHAR(255) NULL,
    model_call_count BIGINT UNSIGNED NOT NULL, model_succeeded_count BIGINT UNSIGNED NOT NULL,
    model_failed_count BIGINT UNSIGNED NOT NULL, model_unresolved_count BIGINT UNSIGNED NOT NULL,
    tool_requested_count BIGINT UNSIGNED NOT NULL, function_call_count BIGINT UNSIGNED NOT NULL,
    mcp_tool_call_count BIGINT UNSIGNED NOT NULL, skill_load_count BIGINT UNSIGNED NOT NULL,
    skill_execution_count BIGINT UNSIGNED NOT NULL, capability_failed_count BIGINT UNSIGNED NOT NULL,
    capability_unresolved_count BIGINT UNSIGNED NOT NULL, known_input_tokens BIGINT UNSIGNED NULL,
    known_output_tokens BIGINT UNSIGNED NULL, known_total_tokens BIGINT UNSIGNED NULL,
    input_known_call_count BIGINT UNSIGNED NOT NULL, output_known_call_count BIGINT UNSIGNED NOT NULL,
    usage_known_call_count BIGINT UNSIGNED NOT NULL, usage_coverage VARCHAR(255) NULL,
    aggregate_usage_reported JSON NULL, details_truncated BOOLEAN NOT NULL,
    dropped_detail_count BIGINT UNSIGNED NOT NULL, revision BIGINT UNSIGNED NOT NULL,
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    instrumentation_version VARCHAR(32) NOT NULL DEFAULT '1', capability_coverage JSON NOT NULL,
    details_expired BOOLEAN NOT NULL DEFAULT FALSE,
    recovery_detected_at TIMESTAMP(6) NULL,
    persisted_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (run_id),
    KEY metrics_runs_session (session_id, started_at, run_id),
    KEY metrics_runs_agent (agent_id, started_at, run_id),
    KEY metrics_runs_provider (provider_id, started_at),
    KEY metrics_runs_instance (instance_id, status, last_observed_at),
    KEY metrics_runs_retention (last_observed_at),
    KEY metrics_runs_started (started_at,run_id),
    KEY metrics_task_time (task_id,started_at),
    KEY metrics_runs_user_time (owner_id,started_at,run_id)
);

CREATE TABLE IF NOT EXISTS metrics_model_calls (
    tool_requested_count BIGINT UNSIGNED NOT NULL DEFAULT 0,
    model_call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    sequence BIGINT UNSIGNED NOT NULL,
    provider_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    requested_model VARCHAR(255) NULL, response_model VARCHAR(255) NULL,
    provider_response_id VARCHAR(255) NULL, status VARCHAR(255) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NULL,
    duration_ms BIGINT UNSIGNED NULL, input_tokens BIGINT UNSIGNED NULL,
    output_tokens BIGINT UNSIGNED NULL, total_tokens BIGINT UNSIGNED NULL,
    cached_input_tokens BIGINT UNSIGNED NULL, cache_write_tokens BIGINT UNSIGNED NULL,
    reasoning_tokens BIGINT UNSIGNED NULL, usage_source VARCHAR(255) NULL,
    finish_reason VARCHAR(255) NULL, error_code VARCHAR(255) NULL, revision BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (model_call_id),
    UNIQUE KEY metrics_model_sequence (run_id, sequence),
    KEY metrics_model_provider (provider_id, started_at)
);

CREATE TABLE IF NOT EXISTS metrics_capability_calls (
    capability_call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    model_call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    invocation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    parent_capability_call_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    provider_tool_call_id VARCHAR(255) NULL, kind VARCHAR(255) NULL, capability_id VARCHAR(255) NULL,
    capability_name VARCHAR(255) NULL, capability_version VARCHAR(255) NULL, server_id VARCHAR(255) NULL,
    connection_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    status VARCHAR(255) NOT NULL,
    started_at TIMESTAMP(6) NOT NULL,
    ended_at TIMESTAMP(6) NULL,
    duration_ms BIGINT UNSIGNED NULL, error_code VARCHAR(255) NULL, revision BIGINT UNSIGNED NOT NULL,
    PRIMARY KEY (capability_call_id),
    KEY metrics_capability_run (run_id, started_at),
    KEY metrics_capability_kind (kind, capability_id, started_at)
);

CREATE TABLE IF NOT EXISTS mcp_registry_lock (id TINYINT NOT NULL PRIMARY KEY);

CREATE TABLE IF NOT EXISTS mcp_servers (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    name VARCHAR(128) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, config JSON NOT NULL,
    enabled BOOLEAN NOT NULL, version BIGINT UNSIGNED NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE KEY mcp_owner_name (owner_id,name),
    KEY mcp_servers_user_time (owner_id,created_at,id)
);

CREATE TABLE IF NOT EXISTS mcp_tool_catalogs (
    server_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    config_version BIGINT UNSIGNED NOT NULL,
    discovered_at TIMESTAMP(6) NOT NULL,
    tools JSON NOT NULL,
    CONSTRAINT mcp_catalog_server FOREIGN KEY (server_id) REFERENCES mcp_servers(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 state VARCHAR(32) NOT NULL, payload JSON NOT NULL, version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 INDEX knowledge_base_state (state,created_at,id),
    KEY knowledge_bases_user_time (owner_id,created_at,id)
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 entry_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(32) NOT NULL, payload JSON NOT NULL, version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 UNIQUE KEY knowledge_document_key (knowledge_base_id,entry_key),
 INDEX knowledge_document_recent (knowledge_base_id,created_at DESC,id ASC),
 FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_document_versions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(32) NOT NULL, payload JSON NOT NULL, version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 INDEX knowledge_revision_doc (document_id,created_at,id),
 INDEX knowledge_revision_cleanup (state,updated_at,id),
 FOREIGN KEY (document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS knowledge_ingestion_jobs (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 state VARCHAR(32) NOT NULL, lease_until TIMESTAMP(6) NULL,
 payload JSON NOT NULL, version BIGINT UNSIGNED NOT NULL DEFAULT 0,
 created_at TIMESTAMP(6) NOT NULL, updated_at TIMESTAMP(6) NOT NULL,
 INDEX knowledge_job_claim (state,lease_until,created_at,id),
 INDEX knowledge_job_page (knowledge_base_id,created_at DESC,id ASC),
 FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS embedding_providers (
    name VARCHAR(100) NOT NULL PRIMARY KEY, type VARCHAR(32) NOT NULL, model_name VARCHAR(255) NOT NULL,
    base_url VARCHAR(2048) NOT NULL, api_key TEXT NULL, dimensions INT NOT NULL, timeout_millis INT NULL,
    max_retries INT NULL, is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_marker TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END) STORED,
    UNIQUE KEY embedding_providers_one_default (default_marker),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS assets (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 payload JSON NOT NULL, created_at TIMESTAMP(6) NOT NULL,
 INDEX asset_owner_recent (owner_id,created_at DESC,id ASC),
 FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS asset_versions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 asset_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 state VARCHAR(24) NOT NULL, lease_until TIMESTAMP(6) NULL,
 lease_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 attempts INT NOT NULL DEFAULT 0, payload JSON NOT NULL,
 md5 CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
 created_at TIMESTAMP(6) NOT NULL,
 INDEX asset_version_jobs(state,lease_until,created_at),
 INDEX asset_version_md5(md5),
 INDEX asset_version_page (asset_id,created_at,id),
 FOREIGN KEY(asset_id) REFERENCES assets(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS asset_knowledge_links (
 revision_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 document_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(asset_version_id) REFERENCES asset_versions(id),
 FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE CASCADE,
 FOREIGN KEY(document_id) REFERENCES knowledge_documents(id) ON DELETE CASCADE,
 INDEX asset_link_version(asset_version_id)
);

CREATE TABLE IF NOT EXISTS asset_chat_refs (
 session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 PRIMARY KEY(session_id,asset_version_id),
 FOREIGN KEY(asset_version_id) REFERENCES asset_versions(id)
);

CREATE TABLE IF NOT EXISTS asset_source_refs (
 run_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 source_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 payload JSON NOT NULL, PRIMARY KEY(run_id,source_id),
 FOREIGN KEY(asset_version_id) REFERENCES asset_versions(id)
);

CREATE TABLE IF NOT EXISTS asset_storage_scopes (
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 knowledge_base_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
 FOREIGN KEY(knowledge_base_id) REFERENCES knowledge_bases(id) ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS asset_artifacts (
 object_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 FOREIGN KEY(asset_version_id) REFERENCES asset_versions(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS identity_users (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    login_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_general_ci NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL, email VARCHAR(254) NOT NULL DEFAULT '', bio TEXT NOT NULL,
    avatar_url VARCHAR(255) NOT NULL DEFAULT '', language VARCHAR(2) NOT NULL DEFAULT 'en', role VARCHAR(20) NOT NULL,
    password_hash VARCHAR(255) NOT NULL, version BIGINT NOT NULL DEFAULT 0,
    auth_version BIGINT NOT NULL DEFAULT 0, created_at BIGINT NOT NULL,
    CONSTRAINT identity_role CHECK (role IN ('SystemAdmin','Normal'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS identity_sessions (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    user_version BIGINT NOT NULL,
    access_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    refresh_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    access_expires_at BIGINT NOT NULL, refresh_expires_at BIGINT NOT NULL,
    INDEX identity_session_expiry (refresh_expires_at),
    CONSTRAINT identity_session_user FOREIGN KEY (user_id) REFERENCES identity_users(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS identity_access_tokens (
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expires_at BIGINT NOT NULL,
    INDEX identity_access_expiry (expires_at),
    CONSTRAINT identity_access_session FOREIGN KEY (session_id) REFERENCES identity_sessions(id) ON DELETE CASCADE
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS skill_definitions (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 name VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
 created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE KEY skill_owner_name(owner_id,name)
);

CREATE TABLE IF NOT EXISTS schema_migrations (
    version VARCHAR(64) NOT NULL PRIMARY KEY,
    applied_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);

-- Generated outputs pin immutable Asset versions without adding retrieval inputs.
CREATE TABLE IF NOT EXISTS asset_session_output_refs (
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (session_id, file_id),
    FOREIGN KEY (asset_version_id) REFERENCES asset_versions(id)
);
