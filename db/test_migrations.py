#!/usr/bin/env python3
"""Exercise fresh installs and legacy upgrades in disposable databases on db/db.sh's MySQL."""
import json
from pathlib import Path
import subprocess
import unittest
import uuid

DB_DIR = Path(__file__).resolve().parent
ADMIN = '00000000-0000-0000-0000-000000000001'
OWNER = '10000000-0000-0000-0000-000000000001'
AGENT = '20000000-0000-0000-0000-000000000001'
SESSION = '30000000-0000-0000-0000-000000000001'
PROVIDER = '40000000-0000-0000-0000-000000000001'
KB = '50000000-0000-0000-0000-000000000001'


def mysql(sql):
    result = subprocess.run([
        'docker', 'exec', '-i', 'mindrails-mysql', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --skip-column-names',
    ], input=sql, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout.strip()


class MigrationTest(unittest.TestCase):
    def setUp(self):
        self.database = 'mindrails_migration_test_' + uuid.uuid4().hex
        mysql(f'CREATE DATABASE {self.database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;')

    def tearDown(self):
        mysql(f'DROP DATABASE {self.database};')

    def run_sql(self, sql):
        return mysql(f'USE {self.database};\n' + sql.replace('USE mindrails;', f'USE {self.database};'))

    def script(self, name):
        return (DB_DIR / name).read_text()

    def migrate(self):
        return self.run_sql(''.join(self.script(name) for name in ['lock.sql', 'schema.sql', 'upgrade.sql', 'bootstrap.sql']))

    def schema(self):
        self.run_sql(self.script('schema.sql'))

    def dump(self):
        return subprocess.run([
            'docker', 'exec', 'mindrails-mysql', 'sh', '-c',
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --user=root --no-create-info '
            '--skip-comments --compact --skip-add-locks --skip-disable-keys --order-by-primary '
            '--hex-blob --no-tablespaces "$1"', 'migration-test', self.database,
        ], text=True, capture_output=True, check=True).stdout

    def insert(self, table, values):
        literal = lambda value: 'NULL' if value is None else "'" + str(value).replace("'", "''") + "'"
        self.run_sql(f'INSERT INTO {table} ({",".join(values)}) VALUES ({",".join(map(literal, values.values()))});')

    def assert_query_indexes(self):
        definitions = self.run_sql("""
            SELECT table_name, index_name,
                   GROUP_CONCAT(CONCAT(column_name, ':', collation) ORDER BY seq_in_index)
            FROM information_schema.statistics
            WHERE table_schema=DATABASE() AND (table_name, index_name) IN (
                ('agents','agents_owner_created'), ('agents','agents_user_time'),
                ('asset_versions','asset_version_page'), ('asset_versions','asset_id'),
                ('assets','asset_owner_recent'), ('assets','asset_owner_page'),
                ('history','idx_history_owner_time'), ('history','history_user_time'),
                ('knowledge_documents','knowledge_document_recent'), ('knowledge_documents','knowledge_document_page'),
                ('knowledge_ingestion_jobs','knowledge_job_page'), ('knowledge_ingestion_jobs','knowledge_base_id'),
                ('sessions','sessions_owner_updated')
            )
            GROUP BY table_name, index_name ORDER BY table_name, index_name;
        """)
        self.assertEqual([
            'agents\tagents_owner_created\towner_id:A,created_at:A,id:A',
            'asset_versions\tasset_version_page\tasset_id:A,created_at:A,id:A',
            'assets\tasset_owner_recent\towner_id:A,created_at:D,id:A',
            'history\tidx_history_owner_time\towner_id:A,started_at:A,id:A',
            'knowledge_documents\tknowledge_document_recent\tknowledge_base_id:A,created_at:D,id:A',
            'knowledge_ingestion_jobs\tknowledge_job_page\tknowledge_base_id:A,created_at:D,id:A',
            'sessions\tsessions_owner_updated\towner_id:A,updated_at:D,id:A',
        ], definitions.splitlines())

    def test_fresh_schema_and_repeat_migration_leave_provider_catalogs_empty(self):
        self.migrate()
        self.assert_query_indexes()
        for catalog in ('providers', 'embedding_providers', 'image_generation_providers', 'web_search_providers'):
            self.assertEqual('0', self.run_sql(f'SELECT COUNT(*) FROM {catalog};'))
        self.assertEqual('1', self.run_sql('SELECT COUNT(*) FROM mcp_registry_lock;'))
        self.assertEqual('0', self.run_sql(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
            "AND table_name IN ('smart_apps','smart_app_revisions','smart_app_releases','smart_app_grants');"))
        before = self.dump()
        self.migrate()
        self.assertEqual(before, self.dump())
        self.assertEqual('6', self.run_sql('SELECT COUNT(*) FROM schema_migrations;'))
        self.assert_agent_status_removed()
        self.assertEqual('1', self.run_sql('SELECT id FROM web_search_provider_catalog;'))
        self.assertEqual('0', self.run_sql("SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE();"))

    def assert_agent_status_removed(self):
        self.assertEqual('0', self.run_sql(
            "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
            "AND table_name='agents' AND column_name='status';"))

    def test_query_index_upgrade_can_resume_with_some_indexes_already_installed(self):
        self.migrate()
        self.run_sql("""
            ALTER TABLE agents RENAME INDEX agents_owner_created TO agents_user_time;
            ALTER TABLE history RENAME INDEX idx_history_owner_time TO history_user_time;
            ALTER TABLE assets DROP INDEX asset_owner_recent, ADD INDEX asset_owner_page(owner_id,created_at,id);
            ALTER TABLE knowledge_documents DROP INDEX knowledge_document_recent,
                ADD INDEX knowledge_document_page(knowledge_base_id,created_at,id);
            ALTER TABLE asset_versions ADD INDEX asset_id(asset_id);
            ALTER TABLE asset_versions DROP INDEX asset_version_page;
            ALTER TABLE knowledge_ingestion_jobs ADD INDEX knowledge_base_id(knowledge_base_id);
            ALTER TABLE knowledge_ingestion_jobs DROP INDEX knowledge_job_page;
            DELETE FROM schema_migrations WHERE version='20261109_query_indexes';
        """)
        self.migrate()
        self.assert_query_indexes()
        self.migrate()
        self.assert_query_indexes()
        self.assertEqual('1', self.run_sql("SELECT COUNT(*) FROM schema_migrations WHERE version='20261109_query_indexes';"))

    def test_embedding_default_upgrade_is_unique_and_preserved_on_repeat(self):
        self.migrate()
        for name in ('qwen-default', 'openai-default'):
            self.insert('embedding_providers', dict(name=name, type='openai', model_name='fixture',
                                                   base_url='https://example.test/v1', dimensions=3))
        self.run_sql("ALTER TABLE embedding_providers DROP INDEX embedding_providers_one_default, DROP COLUMN default_marker, DROP COLUMN is_default; DELETE FROM schema_migrations WHERE version='20261103_embedding_default';")
        self.migrate()
        self.assertEqual('qwen-default', self.run_sql('SELECT name FROM embedding_providers WHERE is_default=TRUE;'))
        with self.assertRaises(RuntimeError):
            self.run_sql("UPDATE embedding_providers SET is_default=TRUE WHERE name='openai-default';")
        self.run_sql("UPDATE embedding_providers SET is_default=FALSE; UPDATE embedding_providers SET is_default=TRUE WHERE name='openai-default';")
        before = self.dump()
        self.migrate()
        self.assertEqual(before, self.dump())
        self.assertEqual('openai-default', self.run_sql('SELECT name FROM embedding_providers WHERE is_default=TRUE;'))

    def test_language_upgrade_preserves_account_preferences_and_legacy_apps(self):
        self.migrate()
        self.run_sql("""
            ALTER TABLE identity_users DROP COLUMN language;
            CREATE TABLE smart_apps (
                id CHAR(36) PRIMARY KEY,
                name VARCHAR(255) NOT NULL,
                language VARCHAR(2) NOT NULL
            );
        """)
        self.insert('identity_users', dict(id=ADMIN, login_id='admin', name='Administrator', email='', bio='',
                                          avatar_url='', role='SystemAdmin', password_hash='test-hash',
                                          created_at=1790600000000))
        self.insert('smart_apps', dict(id=SESSION, name='旧应用', language='zh'))
        self.migrate()
        self.assertEqual('旧应用\tzh', self.run_sql(f"SELECT name,language FROM smart_apps WHERE id='{SESSION}';"))
        self.assertEqual('en', self.run_sql(f"SELECT language FROM identity_users WHERE id='{ADMIN}';"))
        self.run_sql(f"UPDATE identity_users SET language='zh' WHERE id='{ADMIN}';")
        before = self.dump()
        self.migrate()
        self.assertEqual(before, self.dump())

    def test_legacy_data_bindings_owners_credentials_and_indexes_survive(self):
        self.schema()
        self.run_sql("ALTER TABLE agents ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'ARCHIVED';")
        self.insert('providers', dict(id=PROVIDER, name='preserved', integration_mode='OpenAi', streaming=1,
                                     api_key='enc:v1:preserve-ciphertext', model_name='test', sort_order=0))
        self.insert('agents', dict(id=AGENT, owner_id=OWNER, name='中文 Agent', skill_ids='[]', connection_ids='[]',
                                  knowledge_base_ids='[]', status='ARCHIVED', created_at='2026-10-01', updated_at='2026-10-01'))
        self.insert('sessions', dict(id=SESSION, owner_id=OWNER, parent_agent_id=AGENT, provider_id=PROVIDER,
                                    skill_ids='[]', memory_id=SESSION, created_at='2026-10-01', updated_at='2026-10-01'))
        self.insert('history', dict(id=SESSION, owner_id=OWNER, agent_id=AGENT, session_id=SESSION, memory_id=SESSION,
                                   transport='STREAM', status='COMPLETED', messages='[{"text":"历史内容"}]', metadata='{}',
                                   started_at='2026-10-01', finished_at='2026-10-01'))
        self.insert('task_executions', dict(id=SESSION, owner_id=OWNER, agent_id=AGENT, task_id=SESSION,
                                          request_id=SESSION, status='COMPLETED', created_at='2026-10-01', execution='{"result":"保留"}'))
        self.insert('mcp_servers', dict(id=SESSION, name='server', owner_id=OWNER, config='{}', enabled=1,
                                      version=0, created_at='2026-10-01', updated_at='2026-10-01'))
        self.run_sql(f"""
            ALTER TABLE providers CHANGE api_key api_key_ref TEXT NULL;
            ALTER TABLE embedding_providers CHANGE api_key api_key_ref TEXT NULL;
            ALTER TABLE agents DROP COLUMN knowledge_base_ids, ADD knowledge_base_id CHAR(36) NULL;
            UPDATE agents SET knowledge_base_id='{KB}';
            ALTER TABLE sessions DROP INDEX sessions_owner_updated, DROP COLUMN name, ADD knowledge_base_id CHAR(36) NULL;
            UPDATE sessions SET knowledge_base_id='{KB}';
            ALTER TABLE history DROP INDEX idx_history_time, DROP INDEX history_task_time, DROP COLUMN task_id;
            ALTER TABLE history DROP CHECK chk_history_status;
            ALTER TABLE history ADD CONSTRAINT chk_history_status CHECK (status IN ('COMPLETED','FAILED','CANCELLED'));
            ALTER TABLE history MODIFY finished_at DATETIME(6) NOT NULL, MODIFY session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL;
            ALTER TABLE metrics_runs DROP INDEX metrics_runs_started, DROP INDEX metrics_task_time, DROP COLUMN task_id;
            ALTER TABLE metrics_model_calls DROP COLUMN tool_requested_count;
            ALTER TABLE asset_versions DROP INDEX asset_version_md5, DROP COLUMN md5;
            ALTER TABLE mcp_servers DROP INDEX mcp_owner_name, ADD UNIQUE INDEX mcp_server_name(name);
        """)
        for table in ['sessions', 'tasks', 'task_executions', 'mcp_servers', 'knowledge_bases']:
            self.run_sql(f'ALTER TABLE {table} DROP INDEX {table}_user_time, DROP COLUMN owner_id;')
        for table, index in [('agents', 'agents_owner_created'), ('history', 'idx_history_owner_time'),
                             ('metrics_runs', 'metrics_runs_user_time')]:
            self.run_sql(f'ALTER TABLE {table} MODIFY owner_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL, DROP INDEX {index};')
        self.run_sql('UPDATE history SET owner_id=NULL;')
        self.migrate()
        self.assert_agent_status_removed()
        self.assertEqual('enc:v1:preserve-ciphertext', self.run_sql('SELECT api_key FROM providers;'))
        self.assertEqual(KB, self.run_sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(knowledge_base_ids,'$[0]')) FROM agents;"))
        self.assertEqual('2', self.run_sql('SELECT COUNT(*) FROM knowledge_binding_backup_20261017;'))
        for table in ['agents', 'sessions', 'task_executions', 'history']:
            self.assertEqual(OWNER, self.run_sql(f'SELECT owner_id FROM {table};'))
        self.assertEqual(ADMIN, self.run_sql('SELECT owner_id FROM mcp_servers;'))
        self.assertEqual(OWNER, self.run_sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(execution,'$.ownerId')) FROM task_executions;"))
        self.assertEqual('历史内容', self.run_sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(messages,'$[0].text')) FROM history;"))
        # Running archives and tasks without Session identities now fit the upgraded schema.
        self.run_sql("UPDATE history SET status='RUNNING',finished_at=NULL,session_id=NULL;")
        self.assertEqual('0', self.run_sql("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('api_key_ref','knowledge_base_id') AND table_name IN ('providers','embedding_providers','agents','sessions');"))
        self.assertEqual('6', self.run_sql("SELECT COUNT(DISTINCT table_name) FROM information_schema.statistics WHERE table_schema=DATABASE() AND index_name=CONCAT(table_name,'_user_time');"))
        self.assert_query_indexes()
        before = self.dump()
        self.migrate()
        self.assertEqual(before, self.dump())

    def test_partially_migrated_bindings_and_legacy_key_references(self):
        self.schema()
        self.insert('agents', dict(id=AGENT, owner_id=OWNER, name='partial', skill_ids='[]', connection_ids='[]',
                                  knowledge_base_ids=json.dumps([KB]), created_at='2026-10-01', updated_at='2026-10-01'))
        self.run_sql("ALTER TABLE agents ADD knowledge_base_id CHAR(36) NULL; UPDATE agents SET knowledge_base_id='old-binding';")
        self.insert('providers', dict(id=PROVIDER, name='legacy', integration_mode='OpenAi', streaming=1,
                                     api_key='${OLD_KEY}', model_name='test', sort_order=0))
        self.migrate()
        self.assertEqual(KB, self.run_sql("SELECT JSON_UNQUOTE(JSON_EXTRACT(knowledge_base_ids,'$[0]')) FROM agents;"))
        self.assertEqual('old-binding', self.run_sql('SELECT knowledge_base_id FROM knowledge_binding_backup_20261017;'))
        self.assertEqual('1', self.run_sql('SELECT api_key IS NULL FROM providers;'))


if __name__ == '__main__':
    unittest.main(verbosity=2)
