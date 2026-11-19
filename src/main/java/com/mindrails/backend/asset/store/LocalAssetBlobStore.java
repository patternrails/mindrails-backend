package com.mindrails.backend.asset.store;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.*;

public final class LocalAssetBlobStore implements AssetBlobStore {
    private final Path root;

    public LocalAssetBlobStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path path(String key) {
        Path path = root.resolve(AssetBlobStore.key(key)).normalize();
        for (Path p = path; p != null && p.startsWith(root); p = p.getParent())
            if (Files.isSymbolicLink(p))
                throw new IllegalArgumentException("Symlinks are not asset storage");
        return path;
    }

    public void put(String key, InputStream source) {
        Path target = path(key), temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "upload-", ".part");
            try (var out = Files.newOutputStream(temporary)) {
                source.transferTo(out);
            }
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (temporary != null)
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
        }
    }

    public InputStream open(String key) {
        try {
            return Files.newInputStream(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
