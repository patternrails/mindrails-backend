package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.registry.MysqlAssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.LocalAssetBlobStore;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.json.JsonMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

class MysqlAssetStorageIntegrationTest {
    @TempDir Path dir;

    @Test
    void immutableVersionsSurviveServiceRecreationAndReferencesProtectDeletion() throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:3306/mindrails?connectionTimeZone=UTC",
                                "mindrails_app",
                                System.getenv("MINDRAILS_MYSQL_PASSWORD")));
        var json = JsonMapper.builder().build();
        var p = new AssetStorageProperties(new FileStorageProperties(dir));
        var registry = new MysqlAssetRegistry(jdbc, json, p.location());
        var blobs = new LocalAssetBlobStore(p.directory());
        var assets = new DefaultAssetStorageService(registry, blobs, p, json);
        var v =
                assets.upload(
                        "original.txt",
                        null,
                        "FILE",
                        new ByteArrayInputStream(
                                "Original evidence, budget 1200".getBytes(StandardCharsets.UTF_8)),
                        false);
        UUID session = UUID.randomUUID(), run = UUID.randomUUID(), source = UUID.randomUUID();
        try (var worker =
                new AssetWorker(
                        registry,
                        assets,
                        org.mockito.Mockito.mock(AssetKnowledgeService.class),
                        p,
                        new DocumentParserRegistry(new KnowledgeProperties()))) {
            var lease = registry.claim();
            assertThat(lease.version().getId()).isEqualTo(v.getId());
            worker.process(lease);
            assertThat(assets.blocks(v.getId()).getFirst().text()).contains("1200");
            var extraction = v.getExtractionId();
            var replaced =
                    assets.replace(
                            v.getAssetId(),
                            "replacement.txt",
                            new ByteArrayInputStream(
                                    "Replacement evidence, budget 2400"
                                            .getBytes(StandardCharsets.UTF_8)));
            worker.process(registry.claim());
            assertThat(assets.versions(v.getAssetId())).hasSize(2);
            var recreated =
                    new DefaultAssetStorageService(new MysqlAssetRegistry(jdbc, json, p.location()), blobs, p, json);
            assertThat(recreated.version(v.getId()).getExtractionId()).isEqualTo(extraction);
            assertThat(recreated.blocks(v.getId()).getFirst().text()).contains("1200");
            assertThat(recreated.blocks(replaced.getId()).getFirst().text()).contains("2400");
            byte[] png = Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+j46sAAAAASUVORK5CYII=");
            var image = assets.upload("pixel.png", null, "FILE", new ByteArrayInputStream(png), true);
            try {
                var imageLease = registry.claim();
                assertThat(imageLease.version().getId()).isEqualTo(image.getId());
                worker.process(imageLease);
                var ready = recreated.version(image.getId());
                assertThat(ready.getMediaType()).isEqualTo("image/png");
                assertThat(ready.getState()).isEqualTo("READY");
                try (var original = recreated.open(image.getId())) {
                    assertThat(original.readAllBytes()).isEqualTo(png);
                }
            } finally {
                assets.delete(image.getAssetId());
            }
            registry.attach(session, v.getId());
            assertThatThrownBy(() -> assets.delete(v.getAssetId()))
                    .isInstanceOf(AssetException.class);
            registry.detach(session, v.getId());
            registry.source(run, source, v.getId(), Map.of("location", "paragraph 1"));
            assertThatThrownBy(() -> assets.delete(v.getAssetId()))
                    .isInstanceOf(AssetException.class);
            jdbc.update("DELETE FROM asset_source_refs WHERE run_id=?", run.toString());
            assets.delete(v.getAssetId());
            assertThatThrownBy(() -> assets.asset(v.getAssetId()))
                    .isInstanceOf(AssetException.class);
        } finally {
            jdbc.update("DELETE FROM asset_chat_refs WHERE session_id=?", session.toString());
            jdbc.update("DELETE FROM asset_source_refs WHERE run_id=?", run.toString());
            jdbc.update("DELETE FROM assets WHERE id=?", v.getAssetId().toString());
        }
    }
}
