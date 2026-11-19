package com.mindrails.backend.persistence;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.MysqlAgentRegistry;
import com.mindrails.backend.agent.registry.MysqlSessionRegistry;
import com.mindrails.backend.agent.service.internal.SessionForkService;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.Asset;
import com.mindrails.backend.asset.model.AssetVersion;
import com.mindrails.backend.asset.registry.MysqlAssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.LocalAssetBlobStore;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.*;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.provider.ProviderRegistry;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real SQL rollback and Asset reference protection, with no provider calls or external parsing. */
class MysqlSessionForkIntegrationTest {
    @TempDir Path root;
    private final HikariDataSource dataSource = new HikariDataSource();
    private final JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    private final JsonMapper json = new JsonMapper();
    private final UUID agentId = UUID.randomUUID(), owner = UUID.randomUUID(), sessionId = UUID.randomUUID(), memoryId = UUID.randomUUID();
    private final List<UUID> assetIds = new ArrayList<>();
    private final List<UUID> memories = new ArrayList<>(List.of(memoryId));
    private MysqlSessionRegistry sessions;
    private MysqlAssetRegistry assets;
    private SessionDeliverables files;
    private ChatMemoryStoreFactory memory;
    private MysqlHistoryForkStore history;
    private Session source;
    private AssetStorageProperties properties;

    @BeforeEach void setup() {
        dataSource.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails"));
        dataSource.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
        dataSource.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        dataSource.addDataSourceProperty("connectionTimeZone", "UTC");
        dataSource.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        var providers = mock(ProviderRegistry.class);
        when(providers.find(any())).thenAnswer(call -> Optional.of(Provider.builder().id(call.getArgument(0)).build()));
        sessions = new MysqlSessionRegistry(jdbc, json, providers);
        var agents = new MysqlAgentRegistry(jdbc, json, sessions);
        Instant now = Instant.now();
        agents.insertIfAbsent(Agent.builder().id(agentId).ownerId(owner).name("Fork integration")
                .memoryId(UUID.randomUUID()).createdAt(now).updatedAt(now).build());
        source = Session.builder().id(sessionId).memoryId(memoryId).ownerId(owner).parentAgentId(agentId)
                .provider(Provider.builder().id(UUID.randomUUID()).build()).name("Source").createdAt(now).updatedAt(now).build();
        sessions.insertIfAbsent(source);
        properties = new AssetStorageProperties(new FileStorageProperties(root));
        assets = new MysqlAssetRegistry(jdbc, json, properties.location());
        files = new SessionDeliverables(properties, sessions, assets);
        memory = new ChatMemoryStoreFactory(new com.mindrails.backend.memory.ChatMemoryProperties(), jdbc);
        history = new MysqlHistoryForkStore(jdbc);
    }

    @AfterEach void cleanup() {
        try {
            jdbc.update("DELETE r FROM asset_source_refs r JOIN history h ON r.run_id=h.id WHERE h.agent_id=?", agentId.toString());
            for (UUID asset : assetIds) {
                jdbc.update("DELETE r FROM asset_chat_refs r JOIN asset_versions v ON r.asset_version_id=v.id WHERE v.asset_id=?", asset.toString());
                jdbc.update("DELETE r FROM asset_session_output_refs r JOIN asset_versions v ON r.asset_version_id=v.id WHERE v.asset_id=?", asset.toString());
                jdbc.update("DELETE r FROM asset_source_refs r JOIN asset_versions v ON r.asset_version_id=v.id WHERE v.asset_id=?", asset.toString());
            }
            jdbc.update("DELETE FROM history WHERE agent_id=?", agentId.toString());
            jdbc.update("DELETE m FROM chat_memory m JOIN sessions s ON m.memory_id=s.memory_id WHERE s.parent_agent_id=?", agentId.toString());
            for (UUID id : memories) memory.delete(id);
            jdbc.update("DELETE FROM sessions WHERE parent_agent_id=?", agentId.toString());
            jdbc.update("DELETE FROM agents WHERE id=?", agentId.toString());
            for (UUID asset : assetIds) jdbc.update("DELETE FROM assets WHERE id=?", asset.toString());
        } finally { dataSource.close(); }
    }

    private AssetVersion asset(String filename) {
        UUID id = UUID.randomUUID(), version = UUID.randomUUID(); assetIds.add(id);
        var value = AssetVersion.builder().id(version).assetId(id).number(1).state("READY").filename(filename)
                .blobKey(version + "/" + filename).createdAt(Instant.now()).build();
        assets.insert(new Asset(id, owner, filename, "FILE", Instant.now()), value);
        return value;
    }
    private HistoryRecord record() {
        var record = HistoryRecord.builder().id(UUID.randomUUID()).ownerId(owner).agentId(agentId).sessionId(sessionId).memoryId(memoryId)
                .providerId(source.getProvider().getId()).requestId(UUID.randomUUID()).status(HistoryRecord.Status.COMPLETED)
                .transport(HistoryRecord.Transport.STREAM).messages("[]").metadata("{}").startedAt(Instant.now()).finishedAt(Instant.now()).schemaVersion(1).build();
        history.insertFork(record); return record;
    }
    private SessionForkService forks(HistoryForkStore archive) { return new SessionForkService(sessions, memory, archive, null, files, assets, jdbc); }
    private <T> T success(ServiceResult<T> value) { return ((ServiceResult.Success<T>) value).value(); }

    @Test void forkKeepsAttachmentsImagesAndCitationsAfterDeletingSourceAndItsArchive() throws Exception {
        var attachment = asset("input.txt");
        var image = asset("image.png");
        var citation = asset("evidence.txt");
        assets.attach(sessionId, attachment.getId());
        var run = record(); assets.source(run.id(), UUID.randomUUID(), citation.getId(), Map.of("excerpt", "Evidence"));
        Path imagePath = files.resolve(sessionId, "image.png"); Files.createDirectories(imagePath.getParent()); Files.write(imagePath, new byte[]{1, 2, 3});
        var output = files.publishImage(sessionId, imagePath, image.getAssetId(), image.getId(), "image/png", run.requestId());
        // Simulate a manifest created before output reference tracking existed.
        assets.unbindOutput(sessionId, output.id());
        memory.restore(memoryId, List.of(UserMessage.from("Use " + output.downloadUrl())));
        var target = success(forks(history).fork(sessionId)); memories.add(target.getMemoryId());
        var copiedRun = history.session(target.getId()).getFirst();
        assertEquals(run.requestId(), copiedRun.requestId());
        assertEquals(List.of(attachment.getId()), assets.sessionVersions(target.getId()));
        assertEquals(run.requestId(), files.list(target.getId()).getFirst().requestId());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM metrics_runs WHERE session_id=?", Integer.class, target.getId().toString()));
        assertTrue(assets.referenced(image.getAssetId())); // Legacy source was backfilled too.
        files.deleteSession(sessionId); assets.clearSession(sessionId); sessions.remove(sessionId);
        new MysqlHistoryQueryStore(jdbc).delete(run.id()); assets.clearRun(run.id());
        var storage = new DefaultAssetStorageService(assets, new LocalAssetBlobStore(properties.directory()), properties, json);
        for (var asset : List.of(attachment, image, citation)) {
            assertTrue(assets.referenced(asset.getAssetId()));
            assertEquals(AssetException.Reason.CONFLICT, assertThrows(AssetException.class, () -> storage.delete(asset.getAssetId())).reason());
        }
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(files.download(target.getId(), output.id())));
        assertTrue(memory.snapshot(target.getMemoryId()).toString().contains(target.getId().toString()));
        Files.write(files.resolve(target.getId(), "image.png"), new byte[]{4});
        files.publish(target.getId(), files.resolve(target.getId(), "image.png"));
        assertFalse(assets.referenced(image.getAssetId())); // Replacing an image releases only its output reference.
        files.deleteSession(target.getId()); assets.clearSession(target.getId());
        assertFalse(assets.referenced(attachment.getAssetId()));
        assertTrue(assets.referenced(citation.getAssetId())); // Archive lifetime is independent of Session deletion.
        history.deleteFork(copiedRun.id()); assets.clearRun(copiedRun.id());
        assertFalse(assets.referenced(citation.getAssetId()));
    }

    @Test void strictArchiveWriteFailureRollsBackSessionMemoryReferencesAndFiles() throws Exception {
        var attachment = asset("input.txt"); assets.attach(sessionId, attachment.getId());
        var run = record(); assets.source(run.id(), UUID.randomUUID(), attachment.getId(), Map.of());
        Path report = files.resolve(sessionId, "report.txt"); Files.createDirectories(report.getParent()); Files.writeString(report, "Source"); files.publish(sessionId, report);
        memory.restore(memoryId, List.of(UserMessage.from("Original")));
        UUID[] target = new UUID[2];
        var broken = new HistoryForkStore() {
            public List<HistoryRecord> session(UUID id) { return history.session(id); }
            public void insertFork(HistoryRecord record) {
                target[0] = record.sessionId(); target[1] = record.memoryId(); memories.add(target[1]);
                history.insertFork(record);
                assets.copyRunSources(run.id(), record.id());
                throw new IllegalStateException("SQL archive failed after write");
            }
            public void deleteFork(UUID id) { history.deleteFork(id); }
        };
        assertInstanceOf(ServiceResult.Failure.class, forks(broken).fork(sessionId));
        assertTrue(sessions.findManaged(target[0]).isEmpty());
        assertTrue(memory.snapshot(target[1]).isEmpty()); assertTrue(history.session(target[0]).isEmpty());
        assertTrue(assets.sessionVersions(target[0]).isEmpty());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM asset_source_refs WHERE asset_version_id=?", Integer.class, attachment.getId().toString()));
        try (var folders = Files.list(root.resolve("assets/sessions"))) { assertEquals(List.of(sessionId.toString()), folders.map(p -> p.getFileName().toString()).toList()); }
        assertEquals(List.of(UserMessage.from("Original")), memory.snapshot(memoryId));
        assertEquals("Source", Files.readString(report));
    }
}
