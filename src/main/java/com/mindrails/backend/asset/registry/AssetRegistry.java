package com.mindrails.backend.asset.registry;

import com.mindrails.backend.asset.model.Asset;
import com.mindrails.backend.asset.model.AssetVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Transactional metadata boundary; never reads blobs or calls vector services. */
public interface AssetRegistry {
    record Link(UUID revisionId, UUID assetVersionId, UUID knowledgeBaseId, UUID documentId) {}

    record Lease(AssetVersion version, UUID token, int attempts) {}

    <T> T transaction(Supplier<T> fn);

    void insert(Asset a, AssetVersion v);

    void insertVersion(AssetVersion v);

    void lockUploads(UUID owner);

    Optional<AssetVersion> findDuplicate(UUID owner, AssetVersion incoming);

    void backfillMd5(UUID versionId, String md5);

    Asset asset(UUID id);

    void lock(UUID id);

    List<Asset> list(UUID owner, int page, int size);

    AssetVersion version(UUID id);

    List<AssetVersion> versions(UUID asset);

    Lease claim();

    boolean finish(Lease lease, AssetVersion v);

    void retry(UUID version);

    void artifact(UUID version, String key);

    List<String> artifacts(UUID asset);

    void link(Link l);

    List<Link> links(UUID version);

    void unlink(UUID kb, UUID document);

    Optional<UUID> linkedVersion(UUID revision);

    void attach(UUID session, UUID version);

    void detach(UUID session, UUID version);

    void clearSession(UUID session);

    void bindOutput(UUID session, UUID file, UUID version);

    void unbindOutput(UUID session, UUID file);

    void copyRunSources(UUID sourceRun, UUID targetRun);

    void clearRun(UUID run);

    List<UUID> sessionVersions(UUID session);

    void source(UUID run, UUID source, UUID version, Object snapshot);

    boolean referenced(UUID asset);

    void remove(UUID asset);

    UUID scope(UUID owner);

    void scope(UUID owner, UUID kb);
}
