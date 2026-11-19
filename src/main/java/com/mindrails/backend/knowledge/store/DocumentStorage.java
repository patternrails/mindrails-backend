package com.mindrails.backend.knowledge.store;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface DocumentStorage {
    record Stored(String key, String hash, long bytes) {}

    Stored write(UUID revision, String extension, InputStream input);

    default Stored write(UUID revision, String filename, String extension, InputStream input) {
        return write(revision, extension, input);
    }

    default void unlink(UUID knowledgeBase, UUID document) {}

    /** Known upload metadata avoids re-opening an existing asset just to detect its type. */
    default String mediaType(String key) {
        return null;
    }

    default java.util.List<com.mindrails.backend.knowledge.model.DocumentBlock> blocks(String key) {
        return null;
    }

    default Stored reference(UUID assetVersion) {
        throw new UnsupportedOperationException("Assets disabled");
    }

    default void link(UUID revision, UUID knowledgeBase, UUID document, String key) {}

    Path path(String key);

    void text(String key, String content);

    String text(String key);

    void delete(String key);
}
