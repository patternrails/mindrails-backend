package com.mindrails.backend.knowledge;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.service.internal.TestSessionChat;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.QdrantDocumentStore;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeIndexProfile;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.MysqlKnowledgeRegistry;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.store.LocalDocumentStorage;
import com.mindrails.backend.metrics.TestMetrics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Requires db/db.sh and db/qdrant.sh; cleans only random owned records/collections. */
@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class MysqlKnowledgeIntegrationTest {
    @TempDir Path directory;
    DriverManagerDataSource source;
    MysqlKnowledgeRegistry repository;
    KnowledgeTestServices service;
    DefaultKnowledgeBindingService bindings;
    KnowledgeProperties properties;
    AgentRegistry agents;
    SessionRegistry sessions;
    final List<UUID> owned = new ArrayList<>();
    final Set<KnowledgeIndexProfile> profiles = new HashSet<>();
    final JsonMapper mapper = JsonMapper.builder().build();
    final QdrantProperties qdrant = new QdrantProperties();

    @BeforeEach
    void setup() {
        qdrant.setHost(System.getenv().getOrDefault("MINDRAILS_QDRANT_HOST", "localhost"));
        qdrant.setPort(
                Integer.parseInt(System.getenv().getOrDefault("MINDRAILS_QDRANT_PORT", "6333")));
        source =
                new DriverManagerDataSource(
                        System.getenv()
                                .getOrDefault(
                                        "MINDRAILS_MYSQL_URL",
                                        "jdbc:mysql://127.0.0.1:3306/mindrails"),
                        System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"),
                        System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        var connectionProperties = new java.util.Properties();
        connectionProperties.setProperty("connectionTimeZone", "UTC");
        connectionProperties.setProperty("forceConnectionTimeZoneToSession", "true");
        source.setConnectionProperties(connectionProperties);
        repository = new MysqlKnowledgeRegistry(new JdbcTemplate(source), mapper);
        properties = new KnowledgeProperties();

        properties.setMinScore(0.5);
        agents = new AgentRegistryConfiguration().agentRegistry();
        sessions = new SessionRegistryConfiguration().sessionRegistry();
        var provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfAvailable()).thenReturn(repository);
        bindings = new DefaultKnowledgeBindingService(agents, sessions, provider);
        var registry = org.mockito.Mockito.mock(EmbeddingProviderRegistry.class);
        org.mockito.Mockito.when(registry.require(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        new EmbeddingProvider() {
                            public int dimensions() {
                                return 3;
                            }

                            public String fingerprint() {
                                return "test_knowledge_v1";
                            }

                            public List<Double> embed(String text) {
                                return text.contains("cooking")
                                        ? List.of(0.0, 1.0, 0.0)
                                        : List.of(1.0, 0.0, 0.0);
                            }
                        });
        service =
                new KnowledgeTestServices(
                        repository,
                        properties,
                        registry,
                        qdrant,
                        new LocalDocumentStorage(new FileStorageProperties(directory), properties),
                        new DocumentParserRegistry(properties),
                        bindings,
                        mapper);
    }

    KnowledgeBase create() {
        var base = service.create("Test knowledge", "Synthetic docs", "test");
        owned.add(base.getId());
        profiles.add(base.getProfile());
        return base;
    }

    KnowledgeIngestionJob claimOwned(String owner) {
        return repository.claimNextJob(
                owner, properties.getProcessingTimeoutMillis() + 30000, owned.getLast());
    }

    KnowledgeIngestionJob runNext() {
        var job = claimOwned(UUID.randomUUID().toString());
        assertNotNull(job);
        service.process(job);
        return job;
    }

    @AfterEach
    void cleanup() {
        for (UUID id : owned) {
            try {
                for (var doc : repository.listDocuments(id)) {
                    for (var revision : repository.listDocumentVersions(doc.getId())) {
                        profiles.add(revision.getProfile());
                    }
                }
            } catch (Exception ignored) {
            }
            repository.deleteKnowledgeBase(id);
        }
        for (var profile : profiles) {
            try (var index =
                    new QdrantDocumentStore(
                            profile, UUID.randomUUID(), null, qdrant, mapper, false)) {
                index.drop();
            }
        }
    }

    @Test
    void htmlDeckIsParsedEmbeddedAndRetrievedWithStableParagraphLocations() {
        var base = create();
        String html = "<meta charset=utf-8><h1>存储设计</h1><p>业务数据使用 MySQL 保存。</p>"
                + "<script>private script</script><h2>部署计划</h2><p>预算 1200 元。</p>";
        var submitted = com.mindrails.backend.knowledge.service.KnowledgeResults.require(
                service.documents.upload(new com.mindrails.backend.knowledge.model.KnowledgeDocumentUpload(
                        base.getId(), "html-deck", "HTML Deck", List.of(), "deck.html",
                        new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
        runNext();
        assertEquals("READY", service.getDocument(base.getId(), submitted.documentId()).getState().name());
        var hits = service.search(base.getId(), "存储设计与预算");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(hit -> hit.text().contains("1200")));
        assertTrue(hits.stream().allMatch(hit -> hit.location().contains("HTML /")));
        assertTrue(hits.stream().noneMatch(hit -> hit.text().contains("private script")));
    }

    @Test
    void durableIngestQueryDuplicateAndSharedAgentBinding() {
        var base = create();
        var submitted =
                service.text(
                        base.getId(),
                        "guide",
                        "Guide",
                        "# Database\nUse MySQL",
                        List.of(),
                        null,
                        null,
                        null);
        assertFalse(submitted.unchanged());
        var job = runNext();
        assertEquals("DONE", repository.getJob(job.getId()).getState().name());
        var doc = service.getDocument(base.getId(), submitted.documentId());
        assertEquals("READY", doc.getState().name());
        assertEquals(1, service.search(base.getId(), "storage engine").size());
        assertTrue(
                service.text(
                                base.getId(),
                                "guide",
                                "Guide",
                                "# Database\nUse MySQL",
                                List.of(),
                                null,
                                null,
                                null)
                        .unchanged());
        assertThrows(
                KnowledgeException.class,
                () ->
                        service.text(
                                base.getId(),
                                "guide",
                                "Guide",
                                "Changed",
                                List.of(),
                                null,
                                null,
                                null));
        var other = create();
        service.text(
                other.getId(), "guide", "Foreign", "Foreign content", List.of(), null, null, null);
        runNext();
        assertTrue(
                service.search(base.getId(), "engine").stream()
                        .allMatch(hit -> hit.knowledgeBaseId().equals(base.getId())));
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        for (UUID id : List.of(one, two)) {
            agents.insertIfAbsent(
                    Agent.builder()
                            .id(id)
                            .name("Owner")
                            .createdAt(Instant.now())
                            .updatedAt(Instant.now())
                            .build());
        }
        bindings.bind(one, List.of(base.getId()), 0);
        bindings.bind(two, List.of(base.getId()), 0);
        assertEquals(2, service.references(base.getId()).size());
        assertThrows(
                KnowledgeException.class,
                () ->
                        service.deleteBase(
                                base.getId(), service.getKnowledgeBase(base.getId()).getVersion()));
        var newRepository = new MysqlKnowledgeRegistry(new JdbcTemplate(source), mapper);
        assertEquals(
                doc.getActiveVersionId(),
                newRepository.getDocument(doc.getId()).getActiveVersionId());
    }

    @Test
    void failedReplacementKeepsOldSearchAndRebuildPublishesNewGenerationAtomically()
            throws Exception {
        var base = create();
        var submitted =
                service.text(
                        base.getId(),
                        "manual",
                        "Manual",
                        "Original database guide",
                        List.of(),
                        null,
                        null,
                        null);
        runNext();
        var doc = service.getDocument(base.getId(), submitted.documentId());
        var old = doc.getActiveVersionId();
        service.replace(
                base.getId(),
                doc.getId(),
                "broken.pdf",
                new java.io.ByteArrayInputStream("bad pdf".getBytes()),
                doc.getVersion());
        runNext();
        doc = service.getDocument(base.getId(), doc.getId());
        assertEquals("FAILED", doc.getState().name());
        assertEquals(old, doc.getActiveVersionId());
        assertTrue(service.search(base.getId(), "guide").getFirst().text().contains("Original"));
        assertTrue(
                Files.readString(service.download(base.getId(), doc.getId())).contains("Original"));
        var rebuilding =
                service.rebuild(
                        base.getId(),
                        null,
                        600,
                        60,
                        service.getKnowledgeBase(base.getId()).getVersion());
        profiles.add(rebuilding.getPendingProfile());
        assertEquals(
                base.getProfile().generation(),
                service.getKnowledgeBase(base.getId()).getProfile().generation());
        runNext();
        assertEquals("ACTIVE", service.getKnowledgeBase(base.getId()).getState().name());
        assertNotEquals(old, service.getDocument(base.getId(), doc.getId()).getActiveVersionId());
        assertNotEquals(
                base.getProfile().generation(),
                service.getKnowledgeBase(base.getId()).getProfile().generation());
    }

    @Test
    void expiredLeasesRecoverAndDeletionRemovesFilesAndSearch() {
        var base = create();
        var submitted =
                service.text(
                        base.getId(),
                        "guide",
                        "Guide",
                        "Database knowledge",
                        List.of(),
                        null,
                        null,
                        null);
        var expired = claimOwned("old-owner");
        expired.setLeaseUntil(Instant.now().minusSeconds(1));
        repository.save(expired);
        var recovered = claimOwned("new-owner");
        assertEquals(expired.getId(), recovered.getId());
        service.process(expired);
        service.process(recovered);
        var doc = service.getDocument(base.getId(), submitted.documentId());
        assertEquals("READY", doc.getState().name());
        var file = service.download(base.getId(), doc.getId());
        service.deleteDoc(base.getId(), doc.getId(), doc.getVersion());
        runNext();
        assertFalse(Files.exists(file));
        assertTrue(service.search(base.getId(), "knowledge").isEmpty());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void factoryInjectsSourcesAndModelToolsPublishForSubsequentTurns(boolean streaming)
            throws Exception {
        var base = create();
        service.text(
                base.getId(),
                "manual",
                "Manual",
                "Existing database guide",
                List.of(),
                null,
                null,
                null);
        runNext();
        var requests =
                new java.util.concurrent.CopyOnWriteArrayList<
                        dev.langchain4j.model.chat.request.ChatRequest>();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<
                        dev.langchain4j.model.chat.request.ChatRequest,
                        dev.langchain4j.model.chat.response.ChatResponse>
                reply =
                        request -> {
                            requests.add(request);
                            if (count.getAndIncrement() == 0) {
                                return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                        .aiMessage(
                                                dev.langchain4j.data.message.AiMessage.from(
                                                        dev.langchain4j.agent.tool
                                                                .ToolExecutionRequest.builder()
                                                                .id("knowledge-1")
                                                                .name("knowledge_document_create")
                                                                .arguments(
                                                                        "{\"knowledgeBaseId\":\""
                                                                                + base.getId()
                                                                                + "\",\"key\":\"new.guide\",\"title\":\"New"
                                                                                + " guide\",\"content\":\"Reusable"
                                                                                + " deployment"
                                                                                + " procedure\",\"tags\":[]}")
                                                                .build()))
                                        .build();
                            }
                            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                                    .aiMessage(
                                            dev.langchain4j.data.message.AiMessage.from(
                                                    "[\"Reply [K1]\"]"))
                                    .build();
                        };
        var provider =
                com.mindrails.backend.provider.Provider.builder()
                        .id(UUID.randomUUID())
                        .streaming(streaming)
                        .build();
        provider.setChatModel(
                new dev.langchain4j.model.chat.ChatModel() {
                    public dev.langchain4j.model.chat.response.ChatResponse doChat(
                            dev.langchain4j.model.chat.request.ChatRequest request) {
                        return reply.apply(request);
                    }
                });
        provider.setStreamingChatModel(
                new dev.langchain4j.model.chat.StreamingChatModel() {
                    public void doChat(
                            dev.langchain4j.model.chat.request.ChatRequest request,
                            dev.langchain4j.model.chat.response.StreamingChatResponseHandler
                                    handler) {
                        handler.onCompleteResponse(reply.apply(request));
                    }
                });
        var chatMemory = new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
        var factory =
                TestLlmClients.create(
                        List.of(),
                        () -> chatMemory,
                        new com.mindrails.backend.memory.ChatMemoryProperties(),
                        null,
                        null,
                        null,
                        service.conversation);

        var sessionService =
                new DefaultSessionService(
                        sessions);
        var facade =
                new com.mindrails.backend.agent.service.DefaultAgentService(
                        agents,
                        sessionService,
                        new com.mindrails.backend.provider.SnapshotProviderRegistry(
                                List.of(provider)));
        UUID owner = ok(facade.create(Agent.builder().name("Knowledge owner").build()));
        UUID sid = ok(facade.startSession(owner, provider.getId()));
        bindings.bind(owner, List.of(base.getId()), bindings.agent(owner).agentVersion());
        {
            var stream = TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled());
            var events =
                    new java.util.concurrent.CopyOnWriteArrayList<
                            com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent>();
            var latch = new java.util.concurrent.CountDownLatch(1);
            ok(
                    TestSessionChat.start(stream,
                            sid,
                            new com.mindrails.backend.agent.api.websocket.protocol.ClientCommand
                                    .Chat(UUID.randomUUID(), "Preserve a deployment guide"),
                            event -> {
                                events.add(event);
                                if (event
                                                instanceof
                                                com.mindrails.backend.agent.api.websocket.protocol
                                                        .ChatStreamEvent.Completed
                                        || event
                                                instanceof
                                                com.mindrails.backend.agent.api.websocket.protocol
                                                        .ChatStreamEvent.Failed) {
                                    latch.countDown();
                                }
                            }));
            assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertInstanceOf(
                    com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Completed
                            .class,
                    events.getLast());
            assertTrue(
                    events.stream()
                            .filter(
                                    com.mindrails.backend.agent.api.websocket.protocol
                                                    .ChatStreamEvent.Retrieved.class
                                            ::isInstance)
                            .map(
                                    com.mindrails.backend.agent.api.websocket.protocol
                                                    .ChatStreamEvent.Retrieved.class
                                            ::cast)
                            .flatMap(event -> event.sources().stream())
                            .anyMatch(source -> source.url() != null));
        }
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().messages().toString().contains("Existing database guide"));
        assertTrue(requests.getLast().messages().toString().contains("QUEUED"));
        runNext();
        assertTrue(
                service.search(base.getId(), "deployment").stream()
                        .anyMatch(hit -> hit.title().equals("New guide")));
        assertFalse(
                chatMemory
                        .getMessages(sessions.findManaged(sid).orElseThrow().getMemoryId())
                        .stream()
                        .filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                        .anyMatch(
                                message -> message.toString().contains("Existing database guide")));
        new com.mindrails.backend.knowledge.api.web.AgentKnowledgeBindingController(bindings)
                .bind(
                        owner,
                        new com.mindrails.backend.knowledge.api.web.KnowledgeBindingRequest(
                                List.of(), bindings.agent(owner).agentVersion()));
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "MINDRAILS_TEST_QWEN_EMBEDDING",
            matches = "true")
    void realQwenDocumentEmbeddingAndChineseSemanticRetrieval() throws Exception {
        try (var registry =
                com.mindrails.backend.embedding.TestEmbeddingCatalog.qwen(
                        mapper, new org.springframework.core.env.StandardEnvironment())) {
            service =
                    new KnowledgeTestServices(
                            repository,
                            properties,
                            registry,
                            qdrant,
                            new LocalDocumentStorage(new FileStorageProperties(directory), properties),
                            new DocumentParserRegistry(properties),
                            bindings,
                            mapper);
            properties.setMinScore(0.2);
            var base = service.create("Qwen knowledge test", "Synthetic document", "qwen-default");
            owned.add(base.getId());
            profiles.add(base.getProfile());
            service.text(
                    base.getId(),
                    "project.data",
                    "业务存储说明",
                    "# 客户资料持久化\n订单与客户档案统一保存在 MySQL 关系型数据库。\n## 备份\n每天凌晨执行完整备份，备份保留七天。",
                    List.of(),
                    null,
                    null,
                    null);
            runNext();
            service.text(
                    base.getId(), "food", "周末食谱", "烹饪意大利面时加入番茄酱和橄榄油。", List.of(), null, null, null);
            runNext();
            var hits = service.search(base.getId(), "系统采用哪种持久化引擎保存业务数据？");
            assertFalse(hits.isEmpty());
            assertEquals("业务存储说明", hits.getFirst().title());
            assertEquals(1024, base.getProfile().dimensions());
        }
    }

    @Test
    void cancelledRebuildRetainsPublishedVersionAndAllowsFurtherUploads() {
        var base = create();
        var submission =
                service.text(
                        base.getId(),
                        "manual",
                        "Manual",
                        "Current knowledge",
                        List.of(),
                        null,
                        null,
                        null);
        runNext();
        UUID published =
                service.getDocument(base.getId(), submission.documentId()).getActiveVersionId();
        var rebuild =
                service.rebuild(
                        base.getId(),
                        null,
                        500,
                        40,
                        service.getKnowledgeBase(base.getId()).getVersion());
        profiles.add(rebuild.getPendingProfile());
        var cancelled = service.cancelRebuild(base.getId(), rebuild.getVersion());
        assertEquals("ACTIVE", cancelled.getState().name());
        assertNull(cancelled.getPendingProfile());
        assertEquals(
                published,
                service.getDocument(base.getId(), submission.documentId()).getActiveVersionId());
        service.text(base.getId(), "next", "Next", "More knowledge", List.of(), null, null, null);
        runNext();
    }

    private static <T> T ok(com.mindrails.backend.common.ServiceResult<T> result) {
        assertInstanceOf(com.mindrails.backend.common.ServiceResult.Success.class, result);
        return ((com.mindrails.backend.common.ServiceResult.Success<T>) result).value();
    }

    @Test
    void assetBackedKnowledgeRemovalKeepsOriginalAndFixedParsingSnapshot() throws Exception {
        var jdbc = new JdbcTemplate(source);
        var settings = new com.mindrails.backend.asset.config.AssetStorageProperties(new FileStorageProperties(directory));
        var assetsRegistry = new com.mindrails.backend.asset.registry.MysqlAssetRegistry(jdbc, mapper, settings.location());
        var blobs =
                org.mockito.Mockito.spy(new com.mindrails.backend.asset.store.LocalAssetBlobStore(
                        directory.resolve("assets")));
        var assets =
                new com.mindrails.backend.asset.service.DefaultAssetStorageService(
                        assetsRegistry, blobs, settings, mapper);
        var storage =
                new com.mindrails.backend.asset.store.AssetDocumentStorage(
                        assets, blobs, assetsRegistry, new LocalDocumentStorage(new FileStorageProperties(directory), properties));
        var embeddings = org.mockito.Mockito.mock(EmbeddingProviderRegistry.class);
        org.mockito.Mockito.when(embeddings.require(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        new EmbeddingProvider() {
                            public int dimensions() {
                                return 3;
                            }

                            public String fingerprint() {
                                return "asset_integration_v1";
                            }

                            public List<Double> embed(String text) {
                                return List.of(1.0, 0.0, 0.0);
                            }
                        });
        service =
                new KnowledgeTestServices(
                        repository,
                        properties,
                        embeddings,
                        qdrant,
                        storage,
                        new DocumentParserRegistry(properties),
                        bindings,
                        mapper);
        var base = create();
        var submitted =
                service.text(
                        base.getId(),
                        "asset-fixture",
                        "Asset fixture",
                        "Original budget 1200 CNY",
                        List.of(),
                        null,
                        null,
                        null);
        var revision = repository.listDocumentVersions(submitted.documentId()).getFirst();
        UUID version = assetsRegistry.linkedVersion(revision.getId()).orElseThrow();
        assertEquals(base.getId(),assets.asset(assets.version(version).getAssetId()).knowledgeBaseId());
        assertTrue(assets.asset(assets.version(version).getAssetId()).inKnowledgeBase());
        try {
            var parserWorker=new com.mindrails.backend.asset.service.AssetWorker(assetsRegistry,assets,org.mockito.Mockito.mock(com.mindrails.backend.asset.service.AssetKnowledgeService.class),settings,new DocumentParserRegistry(properties));
            parserWorker.close();parserWorker.process(assetsRegistry.claim());
            var extraction=assets.version(version).getExtractionId();var fixedBlocks=assets.blocks(version);
            runNext();
            assertFalse(service.search(base.getId(), "budget").isEmpty());
            org.mockito.Mockito.clearInvocations(blobs);
            assertTrue(service.text(base.getId(), "asset-fixture", "Asset fixture",
                    "Original budget 1200 CNY", List.of(), null, null, null).unchanged());
            org.mockito.Mockito.verifyNoInteractions(blobs);
            var currentBase=service.getKnowledgeBase(base.getId());service.rebuild(base.getId(),"test",256,32,currentBase.getVersion());runNext();
            assertEquals(extraction,assets.version(version).getExtractionId());assertEquals(fixedBlocks,assets.blocks(version));
            service.deleteDoc(
                    base.getId(),
                    submitted.documentId(),
                    service.getDocument(base.getId(), submitted.documentId()).getVersion());
            runNext();
            assertTrue(service.search(base.getId(), "budget").isEmpty());
            assertTrue(assetsRegistry.links(version).isEmpty());
            assertNull(assets.asset(assets.version(version).getAssetId()).knowledgeBaseId());
            try (var original = assets.open(version)) {
                assertTrue(
                        new String(original.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .contains("1200"));
            }
            assertTrue(storage.text(revision.getId() + "/parsed.txt").contains("1200"));
        } finally {
            jdbc.update(
                    "DELETE FROM asset_knowledge_links WHERE asset_version_id=?",
                    version.toString());
            jdbc.update(
                    "DELETE FROM assets WHERE id=?",
                    assets.version(version).getAssetId().toString());
        }
    }

    @Test void explicitVersionScopeCannotRecallOtherFilesFromTheSameKnowledgeBase() {
        var base=create();
        var first=service.text(base.getId(),"allowed-file","Allowed","Original project budget 1200",List.of(),null,null,null);runNext();
        var second=service.text(base.getId(),"other-file","Other","Unrelated project budget 9999",List.of(),null,null,null);runNext();
        var version=service.getDocument(base.getId(),first.documentId()).getActiveVersionId();
        var scoped=com.mindrails.backend.knowledge.service.KnowledgeResults.require(service.search.searchVersions(base.getId(),"budget",Set.of(version)));
        assertFalse(scoped.isEmpty());assertTrue(scoped.stream().allMatch(hit->hit.documentId().equals(first.documentId())));
        assertTrue(scoped.stream().noneMatch(hit->hit.documentId().equals(second.documentId())));
    }
}
