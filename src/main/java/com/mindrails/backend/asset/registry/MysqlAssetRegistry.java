package com.mindrails.backend.asset.registry;

import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.service.AssetException;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/** Metadata only; blob and vector operations stay outside SQL transactions. */
public final class MysqlAssetRegistry implements AssetRegistry {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final String location;

    public MysqlAssetRegistry(JdbcTemplate jdbc, ObjectMapper json, String location) {
        this.jdbc = jdbc;
        this.json = json;
        this.location = location;
        tx =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
    }

    public <T> T transaction(Supplier<T> fn) {
        return tx.execute(s -> fn.get());
    }

    public void insert(Asset a, AssetVersion v) {
        jdbc.update(
                "INSERT INTO assets(id,owner_id,payload,created_at) VALUES(?,?,?,?)",
                a.id().toString(),
                a.ownerId().toString(),
                json.writeValueAsString(a),
                Timestamp.from(a.createdAt()));
        insertVersion(v);
    }

    public void insertVersion(AssetVersion v) {
        jdbc.update(
                "INSERT INTO asset_versions(id,asset_id,state,payload,created_at,md5)"
                        + " VALUES(?,?,?,?,?,?)",
                v.getId().toString(),
                v.getAssetId().toString(),
                v.getState(),
                json.writeValueAsString(v),
                Timestamp.from(v.getCreatedAt()),
                v.getMd5());
    }

    /** Serialize upload publication per owner, including across server instances. */
    public void lockUploads(UUID owner) {
        jdbc.update("INSERT INTO asset_storage_scopes(owner_id) VALUES(?) ON DUPLICATE KEY UPDATE owner_id=VALUES(owner_id)",
                owner.toString());
        jdbc.queryForList("SELECT owner_id FROM asset_storage_scopes WHERE owner_id=? FOR UPDATE",
                owner.toString());
    }

    public Optional<AssetVersion> findDuplicate(UUID owner, AssetVersion incoming) {
        var rows = jdbc.query(
                "SELECT v.id FROM asset_versions v JOIN assets a ON a.id=v.asset_id WHERE"
                        + " a.owner_id=? AND (v.md5=? OR (v.md5 IS NULL AND"
                        + " JSON_UNQUOTE(JSON_EXTRACT(v.payload,'$.hash'))=?)) AND"
                        + " COALESCE(JSON_VALUE(v.payload,'$.storageBackend'),'local')='local'"
                        + " AND JSON_VALUE(v.payload,'$.storageLocation' RETURNING CHAR(4096))=?"
                        + " ORDER BY v.created_at,v.id LIMIT 1",
                (r, n) -> UUID.fromString(r.getString(1)), owner.toString(), incoming.getMd5(),
                incoming.getHash(), location);
        if (rows.isEmpty()) return Optional.empty();
        return Optional.of(version(rows.getFirst()));
    }

    public void backfillMd5(UUID versionId, String md5) {
        jdbc.update("UPDATE asset_versions SET md5=?,payload=JSON_SET(payload,'$.md5',?) WHERE id=?",
                md5, md5, versionId.toString());
    }

    public Asset asset(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT payload,knowledge_base_id FROM assets WHERE id=?",
                        (r, n) -> readAsset(r.getString(1), r.getString(2)),
                        id.toString());
        if (rows.isEmpty()) throw AssetException.missing();
        return rows.getFirst();
    }

    public void lock(UUID id) {
        if (jdbc.queryForList("SELECT id FROM assets WHERE id=? FOR UPDATE", id.toString())
                .isEmpty()) throw AssetException.missing();
    }

    public List<Asset> list(UUID owner, int page, int size) {
        return jdbc.query(
                "SELECT payload,knowledge_base_id FROM assets WHERE owner_id=? ORDER BY created_at"
                        + " DESC,id LIMIT ? OFFSET ?",
                (r, n) -> readAsset(r.getString(1), r.getString(2)),
                owner.toString(),
                size,
                (long) page * size);
    }

    public AssetVersion version(UUID id) {
        var rows =
                jdbc.query(
                        "SELECT payload,state FROM asset_versions WHERE id=?",
                        (r, n) -> {
                            var v = json.readValue(r.getString(1), AssetVersion.class);
                            v.setState(r.getString(2));
                            return v;
                        },
                        id.toString());
        if (rows.isEmpty()) throw AssetException.missing();
        return rows.getFirst();
    }

    private Asset readAsset(String payload, String knowledgeBase) {
        var a = json.readValue(payload, Asset.class);
        return new Asset(
                a.id(),
                a.ownerId(),
                a.title(),
                a.kind(),
                a.createdAt(),
                knowledgeBase == null ? null : UUID.fromString(knowledgeBase));
    }

    public List<AssetVersion> versions(UUID asset) {
        return jdbc.query(
                "SELECT payload,state FROM asset_versions WHERE asset_id=? ORDER BY created_at"
                        + " DESC,id DESC",
                (r, n) -> {
                    var v = json.readValue(r.getString(1), AssetVersion.class);
                    v.setState(r.getString(2));
                    return v;
                },
                asset.toString());
    }

    public Lease claim() {
        return transaction(
                () -> {
                    var rows = jdbc.queryForList(
                            "SELECT id,attempts FROM asset_versions WHERE (state='QUEUED' OR"
                                    + " (state='PROCESSING' AND lease_until<CURRENT_TIMESTAMP(6)))"
                                    + " AND COALESCE(JSON_VALUE(payload,'$.storageBackend'),'local')='local'"
                                    + " AND COALESCE(JSON_VALUE(payload,'$.storageLocation' RETURNING CHAR(4096)),?)=?"
                                    + " ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED",
                            location, location);
                    if (rows.isEmpty()) return null;
                    var row = rows.getFirst();
                    var id = UUID.fromString(row.get("id").toString());
                    var token = UUID.randomUUID();
                    int attempts = ((Number) row.get("attempts")).intValue() + 1;
                    jdbc.update(
                            "UPDATE asset_versions SET"
                                + " state='PROCESSING',lease_until=?,lease_token=?,attempts=? WHERE"
                                + " id=?",
                            Timestamp.from(Instant.now().plusSeconds(300)),
                            token.toString(),
                            attempts,
                            id.toString());
                    return new Lease(version(id), token, attempts);
                });
    }

    public boolean finish(Lease lease, AssetVersion v) {
        return jdbc.update(
                        "UPDATE asset_versions SET"
                            + " state=?,payload=?,lease_until=NULL,lease_token=NULL WHERE id=? AND"
                            + " lease_token=?",
                        v.getState(),
                        json.writeValueAsString(v),
                        v.getId().toString(),
                        lease.token().toString())
                == 1;
    }

    public void retry(UUID version) {
        jdbc.update(
                "UPDATE asset_versions SET state='QUEUED',attempts=0 WHERE id=? AND state='FAILED'",
                version.toString());
    }

    public void artifact(UUID version, String key) {
        jdbc.update(
                "INSERT IGNORE INTO asset_artifacts(object_key,asset_version_id) VALUES(?,?)",
                key,
                version.toString());
    }

    public List<String> artifacts(UUID asset) {
        return jdbc.queryForList(
                "SELECT r.object_key FROM asset_artifacts r JOIN asset_versions v ON"
                        + " v.id=r.asset_version_id WHERE v.asset_id=?",
                String.class,
                asset.toString());
    }

    public void link(Link l) {
        jdbc.update(
                "INSERT INTO"
                    + " asset_knowledge_links(revision_id,asset_version_id,knowledge_base_id,document_id)"
                    + " VALUES(?,?,?,?) ON DUPLICATE KEY UPDATE revision_id=revision_id",
                l.revisionId().toString(),
                l.assetVersionId().toString(),
                l.knowledgeBaseId().toString(),
                l.documentId().toString());
        jdbc.update(
                "UPDATE assets SET knowledge_base_id=? WHERE id=(SELECT asset_id FROM"
                        + " asset_versions WHERE id=?)",
                l.knowledgeBaseId().toString(),
                l.assetVersionId().toString());
    }

    public List<Link> links(UUID version) {
        return jdbc.query(
                "SELECT revision_id,asset_version_id,knowledge_base_id,document_id FROM"
                        + " asset_knowledge_links WHERE asset_version_id=?",
                (r, n) ->
                        new Link(
                                UUID.fromString(r.getString(1)),
                                UUID.fromString(r.getString(2)),
                                UUID.fromString(r.getString(3)),
                                UUID.fromString(r.getString(4))),
                version.toString());
    }

    public void unlink(UUID kb, UUID document) {
        if (document == null)
            jdbc.update(
                    "DELETE FROM asset_knowledge_links WHERE knowledge_base_id=?", kb.toString());
        else
            jdbc.update(
                    "DELETE FROM asset_knowledge_links WHERE knowledge_base_id=? AND document_id=?",
                    kb.toString(),
                    document.toString());
        jdbc.update(
                "UPDATE assets a SET knowledge_base_id=(SELECT l.knowledge_base_id FROM"
                    + " asset_knowledge_links l JOIN asset_versions v ON v.id=l.asset_version_id"
                    + " WHERE v.asset_id=a.id LIMIT 1)");
    }

    public Optional<UUID> linkedVersion(UUID revision) {
        return jdbc
                .queryForList(
                        "SELECT asset_version_id FROM asset_knowledge_links WHERE revision_id=?",
                        String.class,
                        revision.toString())
                .stream()
                .findFirst()
                .map(UUID::fromString);
    }

    public void attach(UUID session, UUID version) {
        jdbc.update(
                "INSERT IGNORE INTO asset_chat_refs(session_id,asset_version_id) VALUES(?,?)",
                session.toString(),
                version.toString());
    }

    public void detach(UUID session, UUID version) {
        jdbc.update(
                "DELETE FROM asset_chat_refs WHERE session_id=? AND asset_version_id=?",
                session.toString(),
                version.toString());
    }

    public void clearSession(UUID session) {
        jdbc.update("DELETE FROM asset_chat_refs WHERE session_id=?", session.toString());
        jdbc.update("DELETE FROM asset_session_output_refs WHERE session_id=?", session.toString());
    }

    public void bindOutput(UUID session, UUID file, UUID version) {
        jdbc.update("INSERT INTO asset_session_output_refs(session_id,file_id,asset_version_id) VALUES(?,?,?)"
                        + " ON DUPLICATE KEY UPDATE asset_version_id=VALUES(asset_version_id)",
                session.toString(), file.toString(), version.toString());
    }

    public void unbindOutput(UUID session, UUID file) {
        jdbc.update("DELETE FROM asset_session_output_refs WHERE session_id=? AND file_id=?", session.toString(), file.toString());
    }

    public void copyRunSources(UUID sourceRun, UUID targetRun) {
        jdbc.update("INSERT INTO asset_source_refs(run_id,source_id,asset_version_id,payload)"
                        + " SELECT ?,source_id,asset_version_id,payload FROM asset_source_refs WHERE run_id=?",
                targetRun.toString(), sourceRun.toString());
    }

    public void clearRun(UUID run) {
        jdbc.update("DELETE FROM asset_source_refs WHERE run_id=?", run.toString());
    }

    public List<UUID> sessionVersions(UUID session) {
        return jdbc
                .queryForList(
                        "SELECT asset_version_id FROM asset_chat_refs WHERE session_id=? ORDER BY"
                                + " created_at,asset_version_id",
                        String.class,
                        session.toString())
                .stream()
                .map(UUID::fromString)
                .toList();
    }

    public void source(UUID run, UUID source, UUID version, Object snapshot) {
        jdbc.update(
                "INSERT IGNORE INTO asset_source_refs(run_id,source_id,asset_version_id,payload)"
                        + " VALUES(?,?,?,?)",
                run.toString(),
                source.toString(),
                version.toString(),
                json.writeValueAsString(snapshot));
    }

    public boolean referenced(UUID asset) {
        Long count =
                jdbc.queryForObject(
                        "SELECT (SELECT COUNT(*) FROM asset_chat_refs r JOIN asset_versions v ON"
                            + " v.id=r.asset_version_id WHERE v.asset_id=?)+(SELECT COUNT(*) FROM"
                            + " asset_source_refs r JOIN asset_versions v ON"
                            + " v.id=r.asset_version_id WHERE v.asset_id=?)+(SELECT COUNT(*) FROM"
                            + " asset_session_output_refs r JOIN asset_versions v ON"
                            + " v.id=r.asset_version_id WHERE v.asset_id=?)+(SELECT COUNT(*) FROM"
                            + " asset_knowledge_links r JOIN asset_versions v ON"
                            + " v.id=r.asset_version_id WHERE v.asset_id=?)",
                        Long.class,
                        asset.toString(),
                        asset.toString(),
                        asset.toString(),
                        asset.toString());
        return count != null && count > 0;
    }

    public void remove(UUID asset) {
        jdbc.update("DELETE FROM assets WHERE id=?", asset.toString());
    }

    public UUID scope(UUID owner) {
        var rows =
                jdbc.queryForList(
                        "SELECT knowledge_base_id FROM asset_storage_scopes WHERE owner_id=? AND"
                                + " knowledge_base_id IS NOT NULL",
                        String.class,
                        owner.toString());
        return rows.isEmpty() ? null : UUID.fromString(rows.getFirst());
    }

    public void scope(UUID owner, UUID kb) {
        jdbc.update(
                "INSERT INTO asset_storage_scopes(owner_id,knowledge_base_id) VALUES(?,?) ON"
                        + " DUPLICATE KEY UPDATE knowledge_base_id=VALUES(knowledge_base_id)",
                owner.toString(),
                kb.toString());
    }
}
