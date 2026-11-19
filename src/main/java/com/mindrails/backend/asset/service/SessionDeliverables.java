package com.mindrails.backend.asset.service;

import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.registry.AssetRegistry;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Manages local Session deliverables, their metadata, downloads and cleanup. */
@Service
public final class SessionDeliverables {
    public record File(
            UUID id, String name, String path, long bytes, String updatedAt, String downloadUrl,
            UUID assetId, UUID versionId, String mediaType, UUID requestId) {
        public File(UUID id, String name, String path, long bytes, String updatedAt, String downloadUrl) {
            this(id, name, path, bytes, updatedAt, downloadUrl, null, null, null, null);
        }
    }

    private final AssetStorageProperties properties;
    private final ReadOnlySessionRegistry sessions;
    private final JsonMapper json = new JsonMapper();
    private final @Nullable AssetRegistry assets;

    public SessionDeliverables(AssetStorageProperties properties, ReadOnlySessionRegistry sessions) {
        this(properties, sessions, null);
    }

    @Autowired
    public SessionDeliverables(AssetStorageProperties properties, ReadOnlySessionRegistry sessions,
            @Nullable AssetRegistry assets) {
        this.properties = properties;
        this.sessions = sessions;
        this.assets = assets;
    }

    void check(UUID session) {
        if (sessions.find(session).isEmpty()) throw AssetException.missing();
    }

    Path directory(UUID session) {
        return properties.directory()
                .resolve("sessions").resolve(session.toString());
    }

    void noSymlinks(Path path) {
        Path root = properties.directory();
        for (Path p = path; p != null && p.startsWith(root); p = p.getParent()) {
            if (Files.isSymbolicLink(p)) {
                throw AssetException.invalid("Symlinks are not session output paths");
            }
        }
    }

    public Path resolve(UUID session, String name) {
        check(session);
        if (name == null || name.isBlank() || name.contains("\\")) {
            throw AssetException.invalid("Use a session-relative output filename");
        }
        return validateFile(session, directory(session).resolve("files").resolve(name));
    }

    private Path validateFile(UUID session, Path path) {
        Path root = directory(session).resolve("files");
        path = path.toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw AssetException.invalid("Output must stay inside this session folder");
        }
        noSymlinks(path);
        return path;
    }

    public Path storageRoot() {
        return properties.storageRoot();
    }

    /** Returns the validated output directory without creating it. */
    public Path outputDirectory(UUID session) {
        check(session);
        Path root = directory(session).resolve("files");
        noSymlinks(root);
        return root;
    }

    /** Registers existing session output files after a successful operation. */
    public List<File> publishAll(UUID session) throws IOException {
        Path root = outputDirectory(session);
        if (!Files.isDirectory(root)) return List.of();
        var published = new ArrayList<File>();
        try (var outputs = Files.walk(root)) {
            for (Path path : outputs.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
                            && !p.getFileName().toString().startsWith(".pending-"))
                    .toList()) {
                published.add(publish(session, path));
            }
        }
        return List.copyOf(published);
    }

    private File describe(UUID session, Path path) throws IOException {
        String relative = directory(session).resolve("files")
                .relativize(path).toString().replace('\\', '/');
        UUID id = UUID.nameUUIDFromBytes(relative.getBytes(StandardCharsets.UTF_8));
        return new File(id, path.getFileName().toString(), relative, Files.size(path),
                Files.getLastModifiedTime(path).toInstant().toString(),
                "/api/asset-storage/sessions/" + session + "/deliverables/" + id + "/download");
    }

    /** Registers an existing file after validating its Session ownership. */
    public File publish(UUID session, Path path) throws IOException {
        return publishImage(session, path, null, null, null);
    }

    /** Generated images retain their Asset identity in the persistent Session manifest. */
    public File publishImage(UUID session, Path path, UUID assetId, UUID versionId, String mediaType) throws IOException {
        return publishImage(session, path, assetId, versionId, mediaType, null);
    }

    public File publishImage(UUID session, Path path, UUID assetId, UUID versionId, String mediaType, UUID requestId) throws IOException {
        check(session);
        path = validateFile(session, path);
        Path metadata = directory(session).resolve("metadata");
        noSymlinks(metadata);
        Files.createDirectories(metadata);
        File file = describe(session, path);
        Path previous = metadata.resolve(file.id() + ".json");
        noSymlinks(previous);
        if (assetId == null && Files.isRegularFile(previous)) {
            File old = json.readValue(Files.readString(previous), File.class);
            if (old.bytes() == file.bytes() && old.updatedAt().equals(file.updatedAt())) {
                assetId = old.assetId(); versionId = old.versionId(); mediaType = old.mediaType();
                requestId = old.requestId();
            }
        }
        file = new File(file.id(), file.name(), file.path(), file.bytes(), file.updatedAt(), file.downloadUrl(), assetId, versionId, mediaType, requestId);
        if (assets == null) return writeMetadata(file, metadata);
        File published = file;
        try {
            return assets.transaction(() -> {
                bindOutput(session, published);
                try { return writeMetadata(published, metadata); }
                catch (IOException error) { throw new UncheckedIOException(error); }
            });
        } catch (UncheckedIOException error) { throw error.getCause(); }
    }

    File writeMetadata(File file, Path metadata) throws IOException {
        Path temporary = Files.createTempFile(metadata, "publish-", ".part");
        try {
            Files.writeString(temporary, json.writeValueAsString(file));
            Files.move(temporary, metadata.resolve(file.id() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return file;
    }

    public List<File> list(UUID session) {
        check(session);
        Path metadata = directory(session).resolve("metadata");
        noSymlinks(metadata);
        if (!Files.isDirectory(metadata)) return List.of();
        try (var paths = Files.list(metadata)) {
            var result = new ArrayList<File>();
            for (Path record : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                noSymlinks(record);
                File file = json.readValue(Files.readString(record), File.class);
                Path path = resolve(session, file.path());
                if (Files.isRegularFile(path)) result.add(file);
            }
            return result.stream()
                    .sorted(Comparator.comparing(File::updatedAt).reversed().thenComparing(File::path))
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    public Path download(UUID session, UUID id) {
        var file = list(session).stream().filter(f -> f.id().equals(id))
                .findFirst().orElseThrow(AssetException::missing);
        return resolve(session, file.path());
    }

    /** Deletes only the named deliverable and its metadata inside the owning Session. */
    public void delete(UUID session, UUID id) {
        Path path = download(session, id);
        Path metadata = directory(session).resolve("metadata").resolve(id + ".json");
        noSymlinks(metadata);
        try {
            Files.delete(path);
            Files.deleteIfExists(metadata);
            if (assets != null) assets.unbindOutput(session, id);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    /** Pins image outputs independently of retrieval attachments, including legacy manifests. */
    public void bindOutput(UUID session, File file) {
        if (assets == null) {
            if (file.versionId() != null) throw AssetException.disabled();
            return;
        }
        if (file.versionId() == null) { assets.unbindOutput(session, file.id()); return; }
        var version = assets.version(file.versionId());
        if (version == null || !version.getAssetId().equals(file.assetId())) throw AssetException.missing();
        assets.lock(file.assetId());
        var asset = assets.asset(file.assetId());
        if (asset == null) throw AssetException.missing();
        com.mindrails.backend.identity.ResourceAccess.requireOwner(asset.ownerId());
        assets.bindOutput(session, file.id(), file.versionId());
    }

    public String rewriteForkResources(String value, UUID source, UUID target) {
        if (value == null) return null;
        String oldRoot = directory(source).resolve("files").toString();
        String newRoot = directory(target).resolve("files").toString();
        // Also handle escaped paths inside serialized messages / tool results (including Windows paths).
        String escapedOld = json.writeValueAsString(oldRoot);
        String escapedNew = json.writeValueAsString(newRoot);
        return value.replace("/api/asset-storage/sessions/" + source + "/deliverables/",
                        "/api/asset-storage/sessions/" + target + "/deliverables/")
                .replace(escapedOld.substring(1, escapedOld.length() - 1), escapedNew.substring(1, escapedNew.length() - 1))
                .replace(oldRoot, newRoot);
    }

    public SessionForkFiles stageFork(UUID source, UUID target) throws IOException {
        return SessionForkFiles.stage(this, source, target);
    }

    @jakarta.annotation.PostConstruct
    public void recoverForkFiles() throws IOException {
        SessionForkFiles.recover(this, sessions);
    }

    void removeTree(Path root) throws IOException {
        noSymlinks(root);
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                noSymlinks(path);
                Files.delete(path);
            }
        }
    }

    /** Called before the Session registry removes the identity; cleanup failures prevent false success. */
    public void deleteSession(UUID session) {
        Path root = directory(session);
        noSymlinks(root);
        try {
            removeTree(root);
            if (assets != null) assets.clearSession(session);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
