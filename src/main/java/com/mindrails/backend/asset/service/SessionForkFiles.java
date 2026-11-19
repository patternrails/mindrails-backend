package com.mindrails.backend.asset.service;

import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.service.SessionDeliverables.File;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Independent file copies, published by one rename and recovered using a fork marker. */
public final class SessionForkFiles implements AutoCloseable {
    private static final JsonMapper json = new JsonMapper();
    private final SessionDeliverables files;
    private final UUID target;
    private final Path staging;
    private boolean published, committed;
    private final List<File> manifest;

    private SessionForkFiles(SessionDeliverables files, UUID target, Path staging, List<File> manifest) {
        this.files = files; this.target = target; this.staging = staging; this.manifest = List.copyOf(manifest);
    }
    public List<File> manifest() { return manifest; }
    public void publish() throws IOException {
        if (staging == null) return;
        if (Files.exists(files.directory(target))) throw new IOException("Fork output folder already exists");
        Files.move(staging, files.directory(target), StandardCopyOption.ATOMIC_MOVE);
        published = true;
    }
    public void committed() {
        // Keep the marker until restart; recovery checks the committed Session identity.
        committed = true;
    }
    @Override public void close() throws IOException {
        if (staging != null && !committed) files.removeTree(published ? files.directory(target) : staging);
    }

    static SessionForkFiles stage(SessionDeliverables files, UUID source, UUID target) throws IOException {
        Path original = files.directory(source);
        files.noSymlinks(original);
        if (!Files.exists(original)) return new SessionForkFiles(files, target, null, List.of());
        files.check(source);
        Path staging = files.directory(target).resolveSibling(".fork-" + target);
        files.noSymlinks(staging);
        Files.createDirectory(staging);
        try {
            Files.writeString(staging.resolve("fork.json"), json.writeValueAsString(target));
            Path sourceFiles = original.resolve("files");
            Path targetFiles = staging.resolve("files");
            var known = new java.util.HashMap<String, File>();
            for (var file : files.list(source)) known.put(file.path(), file);
            var copied = new ArrayList<File>();
            if (Files.exists(sourceFiles)) try (var paths = Files.walk(sourceFiles)) {
                for (Path path : paths.toList()) {
                    files.noSymlinks(path);
                    if (Files.isDirectory(path)) continue;
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unsupported output file: " + path);
                    if (path.getFileName().toString().startsWith(".pending-")) continue;
                    String relative = sourceFiles.relativize(path).toString().replace('\\', '/');
                    Path copy = targetFiles.resolve(relative);
                    Files.createDirectories(copy.getParent());
                    Files.copy(path, copy, StandardCopyOption.COPY_ATTRIBUTES);
                    if (isText(relative)) {
                        try {
                            String text = Files.readString(copy);
                            String rewritten = files.rewriteForkResources(text, source, target);
                            if (!text.equals(rewritten)) Files.writeString(copy, rewritten);
                        } catch (java.nio.charset.CharacterCodingException ignored) { /* Binary content with a text suffix. */ }
                    }
                    UUID id = UUID.nameUUIDFromBytes(relative.getBytes(StandardCharsets.UTF_8));
                    File old = known.get(relative);
                    // An overwritten image must not inherit the previous image's Asset identity.
                    if (old != null && (old.bytes() != Files.size(path)
                            || !old.updatedAt().equals(Files.getLastModifiedTime(path).toInstant().toString()))) old = null;
                    File file = new File(id, path.getFileName().toString(), relative, Files.size(copy),
                            Files.getLastModifiedTime(copy).toInstant().toString(),
                            "/api/asset-storage/sessions/" + target + "/deliverables/" + id + "/download",
                            old == null ? null : old.assetId(), old == null ? null : old.versionId(),
                            old == null ? null : old.mediaType(), old == null ? null : old.requestId());
                    Path metadata = staging.resolve("metadata");
                    Files.createDirectories(metadata);
                    files.writeMetadata(file, metadata);
                    copied.add(file);
                }
            }
            return new SessionForkFiles(files, target, staging, copied);
        } catch (IOException | RuntimeException error) {
            try { files.removeTree(staging); } catch (IOException cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static boolean isText(String name) {
        return name.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(txt|md|html?|css|js|mjs|ts|tsx|jsx|json|csv|tsv|xml|svg|yaml|yml|py|sh|sql)$");
    }

    /** Only folders created by the fork staging protocol are candidates for recovery. */
    static void recover(SessionDeliverables files, ReadOnlySessionRegistry sessions) throws IOException {
        Path root = files.directory(new UUID(0, 0)).getParent();
        files.noSymlinks(root);
        if (!Files.isDirectory(root)) return;
        try (var folders = Files.list(root)) {
            for (Path folder : folders.toList()) {
                if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
                Path marker = folder.resolve("fork.json");
                if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) continue;
                UUID target = json.readValue(Files.readString(marker), UUID.class);
                if (folder.getFileName().toString().equals(".fork-" + target)
                        || (folder.equals(files.directory(target)) && sessions.find(target).isEmpty())) files.removeTree(folder);
                else if (folder.equals(files.directory(target))) Files.delete(marker);
            }
        }
    }

}
