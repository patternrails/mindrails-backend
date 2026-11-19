package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.registry.MysqlAssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

class MysqlAssetDeduplicationIntegrationTest {
    @TempDir Path dir;
    UUID owner;
    com.mindrails.backend.identity.ResourceAccess.Scope ownerScope;
    JdbcTemplate jdbc;
    AssetRegistry registry;
    DefaultAssetStorageService assets;
    AssetBlobStore blobs;
    Set<UUID> created = ConcurrentHashMap.newKeySet();

    @BeforeEach void setup() {
        owner = UUID.randomUUID();
        ownerScope = com.mindrails.backend.identity.ResourceAccess.as(owner);
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:mysql://127.0.0.1:3306/mindrails?connectionTimeZone=UTC",
                "mindrails_app", System.getenv("MINDRAILS_MYSQL_PASSWORD")));
        var json = JsonMapper.builder().build();
        var properties = new AssetStorageProperties(new FileStorageProperties(dir));
        registry = new MysqlAssetRegistry(jdbc, json, properties.location());
        blobs = spy(new LocalAssetBlobStore(dir.resolve("assets")));
        assets = new DefaultAssetStorageService(registry, blobs, properties, json);
    }

    @AfterEach void cleanup() {
        try {
            created.forEach(id -> jdbc.update("DELETE FROM assets WHERE id=?", id.toString()));
            jdbc.update("DELETE FROM asset_storage_scopes WHERE owner_id=?", owner.toString());
        } finally {
            ownerScope.close();
        }
    }

    @Test void identicalBytesReuseOldVersionWithoutReadingCheckingWritingOrRequeueing() throws Exception {
        String text = "Immutable source " + UUID.randomUUID();
        var original = upload("a.html", text);
        assertThat(original.getMd5()).matches("[0-9a-f]{32}");
        assertThat(jdbc.queryForObject("SELECT md5 FROM asset_versions WHERE id=?", String.class,
                original.getId().toString())).isEqualTo(original.getMd5());
        jdbc.update("UPDATE asset_versions SET state='FAILED',payload=JSON_SET(payload,'$.error','original error') WHERE id=?",
                original.getId().toString());
        var current = assets.replace(original.getAssetId(), "new.html", stream(text + " changed"));
        // Simulate an absent original: this feature deliberately trusts DB metadata.
        blobs.delete(original.getBlobKey());
        clearInvocations(blobs);
        var reused = upload("different-name.htm", text);
        assertThat(reused.getId()).isEqualTo(original.getId());
        assertThat(reused.getExtractionId()).isEqualTo(original.getExtractionId());
        assertThat(reused.getState()).isEqualTo("FAILED");
        assertThat(reused.getError()).isEqualTo("original error");
        assertThat(assets.versions(original.getAssetId())).hasSize(2);
        assertThat(assets.replace(original.getAssetId(), "same.html", stream(text + " changed")).getId())
                .isEqualTo(current.getId());
        verifyNoInteractions(blobs);
    }

    @Test void preMd5UploadIsMatchedUsingExistingShaMetadataAndBackfilledWithoutBlobIo() {
        String text = "Legacy " + UUID.randomUUID();
        var first = upload("old.txt", text);
        jdbc.update("UPDATE asset_versions SET md5=NULL,payload=JSON_REMOVE(payload,'$.md5') WHERE id=?",
                first.getId().toString());
        clearInvocations(blobs);
        var reused = upload("new.txt", text);
        assertThat(reused.getId()).isEqualTo(first.getId());
        assertThat(reused.getMd5()).isEqualTo(first.getMd5());
        assertThat(assets.version(first.getId()).getMd5()).isEqualTo(first.getMd5());
        verifyNoInteractions(blobs);
    }

    @Test void localFilesWithLegacyStorageMetadataRemainReadable() throws IOException {
        String text = "Legacy file " + UUID.randomUUID();
        var legacy = upload("legacy.txt", text);
        jdbc.update("UPDATE asset_versions SET state='READY',"
                + "payload=JSON_REMOVE(payload,'$.storageBackend','$.storageLocation') WHERE id=?",
                legacy.getId().toString());

        try (var original = assets.open(legacy.getId())) {
            assertThat(original.readAllBytes()).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void historicalNonLocalFilesAreNotClaimedOrReusedForLocalUploads() throws IOException {
        String text = "Previously remote file " + UUID.randomUUID();
        var remote = upload("old.txt", text);
        jdbc.update("UPDATE asset_versions SET payload=JSON_SET(payload,'$.storageBackend','s3') WHERE id=?",
                remote.getId().toString());

        assertThat(registry.claim()).isNull();
        assertThatThrownBy(() -> assets.open(remote.getId()))
                .isInstanceOf(AssetException.class).hasMessageContaining("local asset directory");
        var local = upload("new.txt", text);
        assertThat(local.getId()).isNotEqualTo(remote.getId());
        assertThat(registry.claim().version().getId()).isEqualTo(local.getId());

        var replacement = assets.replace(remote.getAssetId(), "replacement.txt", stream(text));
        assertThat(replacement.getId()).isNotEqualTo(remote.getId());
        try (var original = assets.open(replacement.getId())) {
            assertThat(original.readAllBytes()).isEqualTo(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test void concurrentDuplicateUploadsPublishOneVersionAndRetainOneBlob() throws Exception {
        String text = "Concurrent " + UUID.randomUUID();
        var writing = new CountDownLatch(2);
        doAnswer(call -> {
            writing.countDown();
            assertThat(writing.await(5, TimeUnit.SECONDS)).isTrue();
            return call.callRealMethod();
        }).when(blobs).put(anyString(), any(InputStream.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<UUID> task = () -> { start.await(); return upload("deck.html", text).getId(); };
            var a = pool.submit(task); var b = pool.submit(task);
            start.countDown();
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
        }
        assertThat(created).hasSize(1);
        verify(blobs, times(2)).put(anyString(), any(InputStream.class));
        verify(blobs, times(1)).delete(anyString());
        try (var files = java.nio.file.Files.walk(dir.resolve("assets"))) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isEqualTo(1);
        }
    }

    @Test void slowFileWriteDoesNotBlockAnotherUploadForTheSameOwner() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive()).isFalse();
            if (writes.getAndIncrement() == 0) {
                entered.countDown();
                assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return call.callRealMethod();
        }).when(blobs).put(anyString(), any(InputStream.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> upload("slow.txt", "slow " + UUID.randomUUID()));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = pool.submit(() -> upload("fast.txt", "fast " + UUID.randomUUID()));
                assertThat(second.get(5, TimeUnit.SECONDS)).isNotNull();
            } finally {
                release.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        }
    }

    @Test void failedFileWriteLeavesNoPublishedMetadataOrPartialBlob() throws Exception {
        doAnswer(call -> {
            call.callRealMethod();
            throw new IllegalStateException("simulated failed upload after partial write");
        }).when(blobs).put(anyString(), any(InputStream.class));
        assertThatThrownBy(() -> upload("failure.txt", "failure " + UUID.randomUUID()))
                .isInstanceOf(AssetException.class);
        assertThat(registry.list(owner, 0, 10)).isEmpty();
        try (var files = java.nio.file.Files.walk(dir.resolve("assets"))) {
            assertThat(files.filter(java.nio.file.Files::isRegularFile).count()).isZero();
        }
    }

    private com.mindrails.backend.asset.model.AssetVersion upload(String name, String text) {
        try (var ignored = com.mindrails.backend.identity.ResourceAccess.as(owner)) {
            var v = assets.upload(name, null, "FILE", stream(text), false);
            created.add(v.getAssetId());
            return v;
        }
    }

    private InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
