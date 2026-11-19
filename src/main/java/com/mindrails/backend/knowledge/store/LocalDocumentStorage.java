package com.mindrails.backend.knowledge.store;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Server-generated opaque keys; clients never provide filesystem paths. */
public final class LocalDocumentStorage implements DocumentStorage {
    private final Path root;
    private final long limit;

    public LocalDocumentStorage(FileStorageProperties files, KnowledgeProperties properties) {
        root = files.knowledge();
        limit = properties.getMaxUploadBytes();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw KnowledgeException.unavailable(e);
        }
    }

    public Stored write(UUID revision, String extension, InputStream input) {
        String key = revision + "/source." + extension;
        Path target = path(key), temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), "upload-", ".part");
            var digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > limit) {
                        throw KnowledgeException.invalid("Document exceeds upload size limit");
                    }
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
            }
            if (total == 0) {
                throw KnowledgeException.invalid("Document is empty");
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            return new Stored(key, HexFormat.of().formatHex(digest.digest()), total);
        } catch (KnowledgeException e) {
            throw e;
        } catch (Exception e) {
            throw KnowledgeException.unavailable(e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    public Path path(String key) {
        if (key == null || !key.matches("[0-9a-f-]{36}/[A-Za-z0-9._-]+")) {
            throw KnowledgeException.invalid("Invalid document storage key");
        }
        var path = root.resolve(key).normalize();
        if (!path.startsWith(root)
                || Files.isSymbolicLink(path.getParent())
                || Files.isSymbolicLink(path)) {
            throw KnowledgeException.invalid("Invalid document path");
        }
        return path;
    }

    public void text(String key, String content) {
        try {
            Files.createDirectories(path(key).getParent());
            Files.writeString(path(key), content);
        } catch (IOException e) {
            throw KnowledgeException.unavailable(e);
        }
    }

    public String text(String key) {
        try {
            return Files.readString(path(key));
        } catch (IOException e) {
            throw KnowledgeException.unavailable(e);
        }
    }

    public void delete(String key) {
        if (key != null) {
            try {
                Files.deleteIfExists(path(key));
            } catch (IOException e) {
                throw KnowledgeException.unavailable(e);
            }
        }
    }
}
