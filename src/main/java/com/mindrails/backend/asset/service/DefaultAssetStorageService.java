package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.store.AssetBlobStore;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

public final class DefaultAssetStorageService implements AssetStorageService {
    private final AssetRegistry registry;
    private final AssetBlobStore blobs;
    private final AssetStorageProperties properties;
    private final ObjectMapper json;
    private final Path cache;
    private final String storageLocation;

    public DefaultAssetStorageService(
            AssetRegistry registry,
            AssetBlobStore blobs,
            AssetStorageProperties properties,
            ObjectMapper json) {
        this.registry = registry;
        this.blobs = blobs;
        this.properties = properties;
        this.json = json;
        storageLocation = properties.location();
        cache =
                properties.cacheDirectory()
                        .resolve(
                                UUID.nameUUIDFromBytes(
                                                ("local:" + storageLocation)
                                                        .getBytes(StandardCharsets.UTF_8))
                                        .toString());
        try {
            Files.createDirectories(cache);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public AssetVersion upload(
            String filename, String title, String kind, InputStream input, boolean autoIndex) {
        UUID id = UUID.randomUUID();
        String safe = filename(filename);
        String label = title == null || title.isBlank() ? safe : title.strip();
        if (label.length() > 160) throw AssetException.invalid("Title exceeds 160 characters");
        var a =
                new Asset(
                        id,
                        com.mindrails.backend.identity.ResourceAccess.creationOwner(),
                        label,
                        "CLIPBOARD".equals(kind) ? "CLIPBOARD" : "FILE",
                        Instant.now());
        return write(id, 1, safe, input, autoIndex, a);
    }

    public AssetVersion replace(UUID assetId, String filename, InputStream input) {
        asset(assetId);
        return write(assetId, 0, filename(filename), input, true, null);
    }

    @Override
    public AssetVersion uploadText(String content, String title) {
        if (content == null || content.isBlank()) throw AssetException.invalid("Text required");
        String filename = "pasted-" + java.time.format.DateTimeFormatter
                .ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now()) + ".txt";
        return upload(filename, title, "CLIPBOARD",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), true);
    }

    private AssetVersion write(
            UUID asset, int number, String filename, InputStream source, boolean autoIndex,
            Asset newAsset) {
        UUID id = UUID.randomUUID();
        Path temp = null;
        String key = id + "/source." + extension(filename);
        boolean[] uploaded = {false};
        try {
            temp = Files.createTempFile(cache, "incoming-", ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            var md5 = MessageDigest.getInstance("MD5");
            long total = 0;
            try (var out = Files.newOutputStream(temp)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = source.read(buffer)) != -1) {
                    total += n;
                    if (total > properties.getMaxUploadBytes())
                        throw AssetException.invalid("File exceeds upload limit");
                    digest.update(buffer, 0, n);
                    md5.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (total == 0) throw AssetException.invalid("File is empty");
            var v = AssetVersion.builder()
                    .id(id)
                    .assetId(asset)
                    .extractionId(UUID.randomUUID())
                    .parserVersion("mindrails-structured-v1")
                    .number(number)
                    .filename(filename)
                    .blobKey(key)
                    .storageBackend("local")
                    .storageLocation(storageLocation)
                    .hash(HexFormat.of().formatHex(digest.digest()))
                    .md5(HexFormat.of().formatHex(md5.digest()))
                    .bytes(total)
                    .state("QUEUED")
                    .autoIndex(autoIndex)
                    .createdAt(Instant.now())
                    .build();
            // Check before uploading so ordinary duplicates need no file I/O.
            var existing = registry.transaction(() -> existingUpload(asset, v, newAsset));
            if (existing != null) return existing;
            v.setMediaType(new org.apache.tika.Tika().detect(temp));
            // Network I/O must not hold the owner's SQL publication lock.
            try (var in = Files.newInputStream(temp)) {
                uploaded[0] = true;
                blobs.put(key, in);
            }
            var published = registry.transaction(() -> {
                // Another upload may have published while this object was being written.
                var duplicate = existingUpload(asset, v, newAsset);
                if (duplicate != null) return duplicate;
                if (newAsset != null) registry.insert(newAsset, v);
                else registry.insertVersion(v);
                return v;
            });
            if (published != v) blobs.delete(key);
            return published;
        } catch (Exception e) {
            if (uploaded[0]) {
                try { blobs.delete(key); }
                catch (RuntimeException cleanup) { e.addSuppressed(cleanup); }
            }
            if (e instanceof AssetException expected) throw expected;
            throw AssetException.unavailable(
                    "Asset upload could not be completed: "
                            + com.mindrails.backend.common.ExceptionDiagnostics.summary(e), e);
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
        }
    }

    /** Called only inside a short metadata transaction. */
    private AssetVersion existingUpload(UUID asset, AssetVersion incoming, Asset newAsset) {
        registry.lockUploads(com.mindrails.backend.identity.ResourceAccess.creationOwner());
        if (newAsset != null) {
            var existing = registry.findDuplicate(newAsset.ownerId(), incoming);
            if (existing.isPresent()) {
                var found = existing.get();
                if (found.getMd5() == null) {
                    registry.backfillMd5(found.getId(), incoming.getMd5());
                    found.setMd5(incoming.getMd5());
                }
                return found;
            }
        } else {
            registry.lock(asset);
            asset(asset);
            var latest = registry.versions(asset).getFirst();
            if (usesLocalStorage(latest)
                    && (Objects.equals(latest.getMd5(), incoming.getMd5())
                            || Objects.equals(latest.getHash(), incoming.getHash()))) return latest;
            incoming.setNumber(latest.getNumber() + 1);
        }
        return null;
    }

    public Asset asset(UUID id) {
        var a = registry.asset(id);
        if (!com.mindrails.backend.identity.ResourceAccess.visible(a.ownerId())) throw AssetException.missing();
        return a;
    }

    public AssetVersion version(UUID id) {
        var v = registry.version(id);
        asset(v.getAssetId());
        return v;
    }

    public List<Asset> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw AssetException.invalid("Invalid page");
        return registry.list(com.mindrails.backend.identity.ResourceAccess.creationOwner(), page, size);
    }

    public List<AssetVersion> versions(UUID id) {
        asset(id);
        return registry.versions(id);
    }

    public List<AssetBlock> blocks(UUID id) {
        var v = version(id);
        if (!"READY".equals(v.getState())) throw AssetException.conflict("Asset text is not ready");
        return parsed(v);
    }

    public List<AssetBlock> parsed(AssetVersion v) {
        requireLocalStorage(v);
        try (var in = blobs.open(blocksKey(v))) {
            return json.readValue(in, new TypeReference<List<AssetBlock>>() {});
        } catch (IOException | RuntimeException error) {
            throw AssetException.unavailable(
                    "Parsed content for asset version " + v.getId()
                            + " is unavailable. Restore its asset files or upload the original again.", error);
        }
    }

    public void parsed(AssetVersion v, List<AssetBlock> blocks) {
        requireLocalStorage(v);
        blobs.put(blocksKey(v), new ByteArrayInputStream(json.writeValueAsBytes(blocks)));
    }

    public static String blocksKey(AssetVersion v) {
        return v.getId() + "/" + v.getExtractionId() + ".json";
    }

    public InputStream open(UUID id) {
        var v = version(id);
        requireLocalStorage(v);
        return blobs.open(v.getBlobKey());
    }

    public Path materialize(String key) {
        AssetBlobStore.key(key);
        try {
            requireLocalStorage(registry.version(UUID.fromString(key.split("/")[0])));
        } catch (AssetException e) {
            if (e.reason() != AssetException.Reason.NOT_FOUND) throw e;
        }
        Path path = cache.resolve(key).normalize();
        for (Path p = path; p != null && p.startsWith(cache); p = p.getParent())
            if (Files.isSymbolicLink(p)) throw AssetException.invalid("Invalid cache path");
        if (Files.exists(path)) return path;
        Path temp = null;
        try {
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), "read-", ".part");
            try (var in = blobs.open(key);
                    var out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            return path;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temp != null)
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
        }
    }

    public void retry(UUID id) {
        var v = version(id);
        requireLocalStorage(v);
        if (!"FAILED".equals(v.getState()))
            throw AssetException.conflict("Only failed parsing can be retried");
        registry.retry(id);
    }

    public void delete(UUID id) {
        registry.transaction(
                () -> {
                    registry.lock(id);
                    asset(id);
                    var versions = registry.versions(id);
                    versions.forEach(this::requireLocalStorage);
                    if (registry.referenced(id))
                        throw AssetException.conflict(
                                "Asset is referenced by a chat, citation or knowledge base");
                    if (versions.stream()
                            .anyMatch(v -> List.of("QUEUED", "PROCESSING").contains(v.getState())))
                        throw AssetException.conflict("Asset is still processing");
                    // Keep metadata until all object deletions succeed; repeated deletes are
                    // idempotent.
                    for (String key : registry.artifacts(id)) {
                        blobs.delete(key);
                        try {
                            Files.deleteIfExists(cache.resolve(key));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    for (var v : versions) {
                        blobs.delete(v.getBlobKey());
                        if ("READY".equals(v.getState())) blobs.delete(blocksKey(v));
                        try {
                            Files.deleteIfExists(cache.resolve(v.getBlobKey()));
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }
                    registry.remove(id);
                    return null;
                });
    }

    public static String filename(String name) {
        if (name == null || name.isBlank()) throw AssetException.invalid("Filename required");
        String safe = name.replace('\\', '/');
        safe = safe.substring(safe.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "");
        if (safe.isBlank() || safe.length() > 240) throw AssetException.invalid("Invalid filename");
        return safe;
    }

    private boolean usesLocalStorage(AssetVersion v) {
        return (v.getStorageBackend() == null || "local".equals(v.getStorageBackend()))
                && (v.getStorageLocation() == null || storageLocation.equals(v.getStorageLocation()));
    }

    private void requireLocalStorage(AssetVersion v) {
        if (!usesLocalStorage(v))
            throw AssetException.conflict("This file is stored outside the configured local asset directory");
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ext.matches("[a-z0-9]{1,12}") ? ext : "bin";
    }
}
