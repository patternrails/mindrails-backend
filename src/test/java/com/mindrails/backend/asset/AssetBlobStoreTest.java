package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;

import com.mindrails.backend.asset.store.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.*;
import java.nio.file.*;

class AssetBlobStoreTest {
    @TempDir Path dir;

    @Test
    void localRoundTripRejectsTraversalAndSymlinks() throws Exception {
        try (var blobs = new LocalAssetBlobStore(dir)) {
            blobs.put(
                    "version/source.txt",
                    new ByteArrayInputStream(
                            "附件内容".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            try (var in = blobs.open("version/source.txt")) {
                assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .isEqualTo("附件内容");
            }
            assertThatThrownBy(() -> blobs.open("../secret"))
                    .isInstanceOf(IllegalArgumentException.class);
            Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("version"));
            assertThatThrownBy(() -> blobs.open("alias/source.txt"))
                    .isInstanceOf(IllegalArgumentException.class);
            blobs.delete("version/source.txt");
            blobs.delete("version/source.txt");
        }
    }
}
