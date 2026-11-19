-- One-time bridge from pre-consolidation schemas. Keep schema.sql free of historical DDL.
USE mindrails;
DELIMITER $$
DROP PROCEDURE IF EXISTS mr_exec$$
CREATE PROCEDURE mr_exec(IN statement_text TEXT)
BEGIN
    SET @mr_sql = statement_text;
    PREPARE mr_statement FROM @mr_sql;
    EXECUTE mr_statement;
    DEALLOCATE PREPARE mr_statement;
END$$
DROP PROCEDURE IF EXISTS mr_column$$
CREATE PROCEDURE mr_column(IN tab VARCHAR(64), IN col VARCHAR(64), IN definition TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=tab AND column_name=col) THEN
        CALL mr_exec(CONCAT('ALTER TABLE `',tab,'` ADD COLUMN `',col,'` ',definition));
    END IF;
END$$
DROP PROCEDURE IF EXISTS mr_index$$
CREATE PROCEDURE mr_index(IN tab VARCHAR(64), IN idx VARCHAR(64), IN definition TEXT, IN cols TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=tab AND index_name=idx) THEN
        CALL mr_exec(CONCAT('ALTER TABLE `',tab,'` ADD ',definition,' `',idx,'` ',cols));
    END IF;
END$$
DROP PROCEDURE IF EXISTS mr_drop_index$$
CREATE PROCEDURE mr_drop_index(IN tab VARCHAR(64), IN idx VARCHAR(64))
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=tab AND index_name=idx) THEN
        CALL mr_exec(CONCAT('ALTER TABLE `',tab,'` DROP INDEX `',idx,'`'));
    END IF;
END$$
DROP PROCEDURE IF EXISTS mr_owner$$
CREATE PROCEDURE mr_owner(IN tab VARCHAR(64), IN parent_col VARCHAR(64), IN time_col VARCHAR(64), IN pk VARCHAR(64))
BEGIN
    CALL mr_column(tab,'owner_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL');
    CALL mr_exec(CONCAT('UPDATE `',tab,'` r ', IF(parent_col IS NULL,'',CONCAT('LEFT JOIN agents a ON a.id=r.',parent_col,' ')),
        'SET r.owner_id=', IF(parent_col IS NULL,QUOTE(@legacy_owner),'COALESCE(a.owner_id,@legacy_owner)'), ' WHERE r.owner_id IS NULL'));
    CALL mr_exec(CONCAT('ALTER TABLE `',tab,'` MODIFY owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT ',QUOTE(@legacy_owner)));
    CALL mr_index(tab,CONCAT(tab,'_user_time'),'INDEX',CONCAT('(owner_id,',time_col,',',pk,')'));
END$$
DROP PROCEDURE IF EXISTS mr_upgrade$$
CREATE PROCEDURE mr_upgrade()
BEGIN
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='20261024_consolidated') THEN
        -- Preserve single-KB bindings before replacing the obsolete columns.
        CALL mr_column('agents','knowledge_base_ids','JSON NULL');
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='agents' AND column_name='knowledge_base_id') THEN
            INSERT IGNORE INTO knowledge_binding_backup_20261017 SELECT 'agent',id,knowledge_base_id FROM agents;
            UPDATE agents SET knowledge_base_ids=IF(knowledge_base_id IS NULL,JSON_ARRAY(),JSON_ARRAY(knowledge_base_id)) WHERE knowledge_base_ids IS NULL;
            ALTER TABLE agents DROP COLUMN knowledge_base_id;
        END IF;
        UPDATE agents SET knowledge_base_ids=JSON_ARRAY() WHERE knowledge_base_ids IS NULL;
        ALTER TABLE agents MODIFY knowledge_base_ids JSON NOT NULL;
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sessions' AND column_name='knowledge_base_id') THEN
            INSERT IGNORE INTO knowledge_binding_backup_20261017 SELECT 'session',id,knowledge_base_id FROM sessions;
            ALTER TABLE sessions DROP COLUMN knowledge_base_id;
        END IF;
        CALL mr_column('sessions','name','VARCHAR(160) NULL AFTER parent_agent_id');
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='providers' AND column_name='api_key_ref') THEN
            ALTER TABLE providers CHANGE COLUMN api_key_ref api_key TEXT NULL;
        END IF;
        IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='embedding_providers' AND column_name='api_key_ref') THEN
            ALTER TABLE embedding_providers CHANGE COLUMN api_key_ref api_key TEXT NULL;
        END IF;
        UPDATE providers SET api_key=NULL WHERE api_key LIKE '${%';
        UPDATE embedding_providers SET api_key=NULL WHERE api_key LIKE '${%';
        ALTER TABLE history MODIFY finished_at DATETIME(6) NULL, MODIFY session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
        IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE constraint_schema=DATABASE() AND table_name='history' AND constraint_name='chk_history_status') THEN
            ALTER TABLE history DROP CHECK chk_history_status;
        END IF;
        ALTER TABLE history ADD CONSTRAINT chk_history_status CHECK (status IN ('RUNNING','COMPLETED','FAILED','CANCELLED'));
        ALTER TABLE metrics_runs MODIFY session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL;
        CALL mr_column('history','task_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL');
        CALL mr_column('metrics_runs','task_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL');
        CALL mr_column('metrics_model_calls','tool_requested_count','BIGINT UNSIGNED NOT NULL DEFAULT 0');
        CALL mr_column('asset_versions','md5','CHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL');
        CALL mr_index('asset_versions','asset_version_md5','INDEX','(md5)');
        CALL mr_index('history','idx_history_time','INDEX','(started_at,id)');
        CALL mr_index('metrics_runs','metrics_runs_started','INDEX','(started_at,run_id)');
        CALL mr_index('history','history_task_time','INDEX','(task_id,started_at)');
        CALL mr_index('metrics_runs','metrics_task_time','INDEX','(task_id,started_at)');
        SET @legacy_owner='00000000-0000-0000-0000-000000000001';
        CALL mr_owner('agents',NULL,'created_at','id');
        CALL mr_owner('sessions','parent_agent_id','created_at','id');
        CALL mr_owner('tasks','parent_agent_id','created_at','id');
        CALL mr_owner('task_executions','agent_id','created_at','id');
        CALL mr_owner('history','agent_id','started_at','id');
        CALL mr_owner('metrics_runs','agent_id','started_at','run_id');
        CALL mr_owner('mcp_servers',NULL,'created_at','id');
        CALL mr_owner('knowledge_bases',NULL,'created_at','id');
        UPDATE task_executions SET execution=JSON_SET(execution,'$.ownerId',owner_id) WHERE JSON_EXTRACT(execution,'$.ownerId') IS NULL OR JSON_TYPE(JSON_EXTRACT(execution,'$.ownerId'))='NULL';
        IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='mcp_servers' AND index_name='mcp_server_name') THEN
            ALTER TABLE mcp_servers DROP INDEX mcp_server_name;
        END IF;
        CALL mr_index('mcp_servers','mcp_owner_name','UNIQUE INDEX','(owner_id,name)');
        INSERT INTO schema_migrations(version) VALUES ('20261024_consolidated');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='20261103_embedding_default') THEN
        CALL mr_column('embedding_providers','is_default','BOOLEAN NOT NULL DEFAULT FALSE');
        IF NOT EXISTS (SELECT 1 FROM embedding_providers WHERE is_default=TRUE) THEN
            UPDATE embedding_providers SET is_default=TRUE WHERE name='qwen-default';
        END IF;
        CALL mr_column('embedding_providers','default_marker','TINYINT GENERATED ALWAYS AS (CASE WHEN is_default THEN 1 ELSE NULL END) STORED');
        CALL mr_index('embedding_providers','embedding_providers_one_default','UNIQUE INDEX','(default_marker)');
        INSERT INTO schema_migrations(version) VALUES ('20261103_embedding_default');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='20261109_query_indexes') THEN
        CALL mr_index('sessions','sessions_owner_updated','INDEX','(owner_id,updated_at DESC,id ASC)');
        CALL mr_index('asset_versions','asset_version_page','INDEX','(asset_id,created_at,id)');
        CALL mr_index('knowledge_ingestion_jobs','knowledge_job_page','INDEX','(knowledge_base_id,created_at DESC,id ASC)');
        CALL mr_index('assets','asset_owner_recent','INDEX','(owner_id,created_at DESC,id ASC)');
        CALL mr_index('knowledge_documents','knowledge_document_recent','INDEX','(knowledge_base_id,created_at DESC,id ASC)');
        CALL mr_index('agents','agents_owner_created','INDEX','(owner_id,created_at,id)');
        CALL mr_index('history','idx_history_owner_time','INDEX','(owner_id,started_at,id)');

        -- Install replacements before removing indexes used by older schemas.
        CALL mr_drop_index('asset_versions','asset_id');
        CALL mr_drop_index('knowledge_ingestion_jobs','knowledge_base_id');
        CALL mr_drop_index('assets','asset_owner_page');
        CALL mr_drop_index('knowledge_documents','knowledge_document_page');
        CALL mr_drop_index('agents','agents_user_time');
        CALL mr_drop_index('history','history_user_time');
        INSERT INTO schema_migrations(version) VALUES ('20261109_query_indexes');
    END IF;
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='20261113_web_search_providers') THEN
        -- schema.sql installs the catalog before upgrades on fresh and existing databases.
        INSERT INTO schema_migrations(version)
            SELECT '20261113_web_search_providers' FROM web_search_provider_catalog WHERE id=1;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM schema_migrations WHERE version='20261117_remove_agent_status') THEN
        IF EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema=DATABASE() AND table_name='agents' AND column_name='status') THEN
            ALTER TABLE agents DROP COLUMN status;
        END IF;
        INSERT INTO schema_migrations(version) VALUES ('20261117_remove_agent_status');
    END IF;
END$$
CALL mr_upgrade()$$
-- Keep the legacy discriminators available for registry queries.
CALL mr_column('agents','special_key','VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NULL')$$
CALL mr_column('sessions','smart_app','JSON NULL')$$
CALL mr_column('identity_users','language',"VARCHAR(2) NOT NULL DEFAULT 'en'")$$
INSERT IGNORE INTO schema_migrations(version) VALUES ('20261029_language_preferences')$$
CALL mr_column('metrics_runs','app_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL')$$
CALL mr_column('metrics_runs','app_revision_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL')$$
CALL mr_column('metrics_runs','app_release_id','CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL')$$
CALL mr_column('metrics_runs','app_mode','VARCHAR(16) NULL')$$
DROP PROCEDURE mr_upgrade$$
DROP PROCEDURE mr_owner$$
DROP PROCEDURE mr_drop_index$$
DROP PROCEDURE mr_index$$
DROP PROCEDURE mr_column$$
DROP PROCEDURE mr_exec$$
DELIMITER ;

-- Generated outputs pin immutable Asset versions without adding retrieval inputs.
CREATE TABLE IF NOT EXISTS asset_session_output_refs (
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    file_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    asset_version_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY (session_id, file_id),
    FOREIGN KEY (asset_version_id) REFERENCES asset_versions(id)
);
