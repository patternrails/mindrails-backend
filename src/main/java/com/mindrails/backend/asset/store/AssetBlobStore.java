package com.mindrails.backend.asset.store;

import java.io.InputStream;

/** Opaque server-generated keys only. No client paths or URLs. */
public interface AssetBlobStore extends AutoCloseable {
    void put(String key, InputStream source);

    InputStream open(String key);

    void delete(String key);

    @Override
    default void close() {}

    static String key(String key) {
        if (key == null
                || !key.matches("[a-zA-Z0-9_-]+(?:/[a-zA-Z0-9._-]+)+")
                || key.contains("..")) throw new IllegalArgumentException("Invalid asset key");
        return key;
    }
}
