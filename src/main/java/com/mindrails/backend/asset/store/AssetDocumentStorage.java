package com.mindrails.backend.asset.store;

import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.AssetStorageService;
import com.mindrails.backend.knowledge.store.DocumentStorage;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.UUID;

/** Existing Knowledge parsers keep Path access; raw files now belong to Assets. */
public final class AssetDocumentStorage implements DocumentStorage {
    private final AssetStorageService assets;
    private final AssetBlobStore blobs;
    private final AssetRegistry registry;
    private final DocumentStorage legacy;

    public AssetDocumentStorage(
            AssetStorageService assets,
            AssetBlobStore blobs,
            AssetRegistry registry,
            DocumentStorage legacy) {
        this.assets = assets;
        this.blobs = blobs;
        this.registry = registry;
        this.legacy = legacy;
    }

    public Stored write(UUID revision, String ext, InputStream input) {
        var v = assets.upload("document." + ext, null, "FILE", input, false);
        return new Stored(v.getBlobKey(), v.getHash(), v.getBytes());
    }

    public Stored write(UUID revision, String filename, String ext, InputStream input) {
        var v = assets.upload(filename, null, "FILE", input, false);
        return new Stored(v.getBlobKey(), v.getHash(), v.getBytes());
    }

    public void unlink(UUID kb, UUID document) {
        registry.unlink(kb, document);
    }

    public String mediaType(String key) {
        try {
            String known = assets.version(UUID.fromString(key.split("/")[0])).getMediaType();
            return known == null ? "application/octet-stream" : known;
        } catch (com.mindrails.backend.asset.service.AssetException missing) {
            if (missing.reason() != com.mindrails.backend.asset.service.AssetException.Reason.NOT_FOUND) throw missing;
            return legacy.mediaType(key);
        }
    }

    public Stored reference(UUID version) {
        var v = assets.version(version);
        return new Stored(v.getBlobKey(), v.getHash(), v.getBytes());
    }

    public java.util.List<com.mindrails.backend.knowledge.model.DocumentBlock> blocks(String key) {
        try {
            var version = assets.version(UUID.fromString(key.split("/")[0]));
            if (!"READY".equals(version.getState())) return null;
            return assets.blocks(version.getId()).stream()
                    .map(
                            block ->
                                    new com.mindrails.backend.knowledge.model.DocumentBlock(
                                            block.text(), block.location()))
                    .toList();
        } catch (com.mindrails.backend.asset.service.AssetException legacy) {
            return null;
        }
    }

    public void link(UUID revision, UUID kb, UUID document, String key) {
        try {
            UUID version = UUID.fromString(key.split("/")[0]);
            assets.version(version);
            registry.link(new AssetRegistry.Link(revision, version, kb, document));
        } catch (com.mindrails.backend.asset.service.AssetException missing) {
            /* Legacy file is still served by the old store. */
        }
    }

    public Path path(String key) {
        try {
            return assets.materialize(key);
        } catch (RuntimeException e) {
            var path = legacy.path(key);
            if (!java.nio.file.Files.isRegularFile(path)) throw e;
            return path;
        }
    }

    public void text(String key, String text) {
        blobs.put(key, new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        registry.linkedVersion(UUID.fromString(key.split("/")[0]))
                .ifPresent(version -> registry.artifact(version, key));
    }

    public String text(String key) {
        try (var in = blobs.open(key)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return legacy.text(key);
        }
    }

    // Knowledge deletion removes vectors/relationships, never the asset original or snapshots.
    public void delete(String key) {}
}
