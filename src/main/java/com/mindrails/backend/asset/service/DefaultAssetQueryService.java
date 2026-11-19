package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.model.AssetBlock;
import com.mindrails.backend.asset.model.AssetDetails;
import com.mindrails.backend.asset.model.AssetSource;
import com.mindrails.backend.asset.registry.AssetRegistry;

import java.util.List;
import java.util.UUID;

/** Coordinates metadata and indexing status without exposing registries to the Web layer. */
public final class DefaultAssetQueryService implements AssetQueryService {
    private final AssetStorageService assets;
    private final AssetRegistry registry;
    private final AssetKnowledgeService knowledge;

    public DefaultAssetQueryService(
            AssetStorageService assets, AssetRegistry registry, AssetKnowledgeService knowledge) {
        this.assets = assets;
        this.registry = registry;
        this.knowledge = knowledge;
    }

    @Override
    public AssetDetails get(UUID assetId) {
        return details(assetId, null);
    }

    @Override
    public AssetDetails version(UUID versionId) {
        var version = assets.version(versionId);
        return details(version.getAssetId(), versionId);
    }

    @Override
    public List<AssetDetails> list(int page, int size) {
        return assets.list(page, size).stream().map(asset -> get(asset.id())).toList();
    }

    private AssetDetails details(UUID assetId, UUID versionId) {
        var asset = assets.asset(assetId);
        var versions = assets.versions(assetId);
        var version = versionId == null ? versions.getFirst() : versions.stream()
                .filter(item -> item.getId().equals(versionId))
                .findFirst().orElseThrow(AssetException::missing);
        var links = versions.stream()
                .flatMap(item -> registry.links(item.getId()).stream()).toList();
        var status = knowledge.status(version.getId());
        return new AssetDetails(
                asset, version, !links.isEmpty(),
                links.isEmpty() ? null : links.getFirst().knowledgeBaseId(),
                status.state(), status.error(), versions);
    }

    @Override
    public List<AssetBlock> blocks(UUID versionId, int page, int size) {
        if (page < 0 || size < 1 || size > 200) throw AssetException.invalid("Invalid page");
        return assets.blocks(versionId).stream().skip((long) page * size).limit(size).toList();
    }

    @Override
    public AssetSource source(UUID versionId, UUID extractionId, UUID blockId) {
        var version = assets.version(versionId);
        if (!version.getExtractionId().equals(extractionId)) throw AssetException.missing();
        var block = assets.blocks(versionId).stream()
                .filter(item -> item.id().equals(blockId))
                .findFirst().orElseThrow(AssetException::missing);
        return new AssetSource(version, block);
    }
}
