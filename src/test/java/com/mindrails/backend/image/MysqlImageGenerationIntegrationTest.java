package com.mindrails.backend.image;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.tool.spec.ImageGenerationTools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.core.env.StandardEnvironment;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Disposable MySQL database; no existing provider catalogs/defaults are changed. */
class MysqlImageGenerationIntegrationTest {
    final String database = "mindrails_image_test_" + UUID.randomUUID().toString().replace("-", "");
    HikariDataSource source;
    JdbcTemplate jdbc;
    ImageGenerationClient remote;
    ImageProviderService service;

    static void mysql(String sql) throws Exception {
        var process = new ProcessBuilder("docker", "exec", "-i", "mindrails-mysql", "sh", "-c",
                "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --user=root --batch --skip-column-names").redirectErrorStream(true).start();
        try (var input = process.getOutputStream()) { input.write(sql.getBytes(StandardCharsets.UTF_8)); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }
    @BeforeEach void start() throws Exception {
        mysql("CREATE DATABASE " + database + "; GRANT ALL PRIVILEGES ON " + database + ".* TO 'mindrails_app'@'%';\n"
                + Files.readString(Path.of("db/schema.sql")).replace("USE mindrails;", "USE " + database + ";"));
        source = new HikariDataSource(); source.setJdbcUrl("jdbc:mysql://127.0.0.1:3306/" + database);
        source.setUsername("mindrails_app"); source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone", "UTC");
        source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        jdbc = new JdbcTemplate(source); remote = mock(ImageGenerationClient.class);
        service = new ImageProviderService(jdbc, new StandardEnvironment(), remote);
    }
    @AfterEach void stop() throws Exception { if (source != null) source.close(); mysql("DROP DATABASE IF EXISTS " + database); }
    ImageProvider.Input input(String name, boolean makeDefault, String key) {
        return new ImageProvider.Input(name, "openai", "https://api.openai.com/v1/images/generations", key,
                "gpt-image-2.5-flare", makeDefault, true, "1024x1024", true, 2000);
    }
    @Test void saveTestsEveryTimeAndPersistsEncryptedKeyStatusAndHistoryAcrossServiceRecreation() {
        var saved = service.save(null, input("first", true, "private-image-key"));
        assertTrue(saved.tested()); assertTrue(saved.testSuccess()); assertEquals(saved.id(), service.select(null).info().id());
        assertFalse(new JsonMapper().writeValueAsString(saved).contains("private-image-key"));
        var encrypted = jdbc.queryForObject("SELECT api_key FROM image_generation_providers WHERE id=?", String.class, saved.id().toString());
        assertTrue(encrypted.startsWith("enc:v1:")); assertFalse(encrypted.contains("private-image-key"));
        service = new ImageProviderService(jdbc, new StandardEnvironment(), remote);
        assertEquals("private-image-key", service.config(saved.id()).apiKey());
        var updated = service.save(saved.id(), input("renamed", true, null));
        assertEquals(1, updated.version()); assertEquals("private-image-key", service.config(saved.id()).apiKey());
        assertEquals(2, service.tests(saved.id()).size());
        verify(remote, times(2)).checkModel(any());
        verify(remote, never()).generate(any(), anyString(), any());
        assertTrue(service.test(saved.id(), true).connected());
        verify(remote).generate(any(), anyString(), any());
    }
    @Test void failedTestsArePersistedAndOnlyOneDefaultExistsWithoutSilentProviderSubstitution() {
        var first = service.save(null, input("first", true, "private-key"));
        var second = service.save(null, input("second", true, "private-key"));
        assertFalse(service.get(first.id()).defaultProvider()); assertTrue(service.get(second.id()).defaultProvider());
        assertThrows(ImageGenerationException.class, () -> service.select("other-vendor"));
        assertThrows(ImageGenerationException.class, () -> service.select("openai"));
        doThrow(new RuntimeException("private-key secret remote content")).when(remote).checkModel(any());
        var failed = service.test(second.id()); assertFalse(failed.connected());
        assertFalse(failed.message().contains("private-key"));
        assertTrue(service.get(second.id()).tested()); assertFalse(service.get(second.id()).testSuccess());
        assertEquals(List.of(first.id()), service.available().stream().map(ImageProvider.Info::id).toList());
        assertThrows(ImageGenerationException.class, () -> service.select(null));
        assertEquals(first.id(), service.select("first").info().id());
        assertEquals(2, service.tests(second.id()).size());
        service.delete(second.id()); assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM image_generation_provider_tests WHERE provider_id=?", Integer.class, second.id().toString()));
        assertFalse(service.get(first.id()).defaultProvider());
    }
    @Test void concurrentInitialSavesSerializeDefaultsAcrossServiceInstances() throws Exception {
        var other = new ImageProviderService(jdbc, new StandardEnvironment(), remote);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return service.save(null, input("first", true, "key-one")); });
            var second = executor.submit(() -> { start.await(); return other.save(null, input("second", true, "key-two")); });
            start.countDown(); first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        }
        assertEquals(2, service.available().size());
        assertEquals(1, service.list(0, 20).stream().filter(ImageProvider.Info::defaultProvider).count());
    }

    @Test void aLateSuccessfulTestCannotValidateAnEditedConfiguration() throws Exception {
        var first = service.save(null, input("first", true, "private-key"));
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            var config = (ImageProvider.Config) invocation.getArgument(0);
            if (config.info().version() == 0) {
                entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
                return null;
            }
            throw new IllegalStateException("new settings fail");
        }).when(remote).checkModel(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var oldTest = executor.submit(() -> service.test(first.id()));
            try {
                assertTrue(entered.await(3, TimeUnit.SECONDS));
                var newer = service.save(first.id(), input("changed", true, null));
                assertFalse(newer.testSuccess()); assertEquals(1, newer.version());
            } finally { release.countDown(); }
            assertTrue(oldTest.get(5, TimeUnit.SECONDS).connected());
        }
        assertFalse(service.get(first.id()).testSuccess()); assertTrue(service.available().isEmpty());
        assertEquals(3, service.tests(first.id()).size());
    }
    @Test void generatedImageBecomesAnOwnedAssetAndDeliverableWithoutOcrOrEmbedding(@org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        service.save(null, input("images", true, "image-key"));
        when(remote.generate(any(), anyString(), any(ImageGenerationOptions.class), any())).thenReturn(List.of(new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png")));
        var sessions = new com.mindrails.backend.agent.registry.SessionRegistryConfiguration().sessionRegistry();
        var owner = UUID.randomUUID();
        var session = com.mindrails.backend.agent.model.Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
                .ownerId(owner).memoryId(UUID.randomUUID()).build();
        sessions.insertIfAbsent(session);
        var properties = new com.mindrails.backend.asset.config.AssetStorageProperties(new FileStorageProperties(directory));
        var registry = new com.mindrails.backend.asset.registry.MysqlAssetRegistry(jdbc, new JsonMapper(), properties.location());
        var assets = new com.mindrails.backend.asset.service.DefaultAssetStorageService(registry,
                new com.mindrails.backend.asset.store.LocalAssetBlobStore(properties.directory()), properties, new JsonMapper());
        var files = new com.mindrails.backend.asset.service.SessionDeliverables(properties, sessions);
        var tool = new ImageGenerationTools(service, remote, assets, files, sessions, com.mindrails.backend.metrics.TestMetrics.disabled());
        var context = dev.langchain4j.invocation.InvocationContext.builder().chatMemoryId(session.getMemoryId())
                .invocationParameters(dev.langchain4j.invocation.InvocationParameters.from(Map.of(com.mindrails.backend.agent.model.LlmClient.SESSION_ID, session.getId()))).build();
        try (var scope = com.mindrails.backend.identity.ResourceAccess.as(owner)) {
            var supplied = tool.provideTools(dev.langchain4j.service.tool.ToolProviderRequest.builder()
                    .userMessage(dev.langchain4j.data.message.UserMessage.from("draw a cat")).invocationContext(context).build()).aiServiceTools().getFirst();
            var result = supplied.toolExecutor().executeWithContext(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("image-call").name("image_generate").arguments("{\"prompt\":\"draw a cat\"}").build(), context);
            var json = new JsonMapper().readTree(result.resultText());
            assertTrue(json.path("success").asBoolean(), result.resultText());
            UUID versionId = UUID.fromString(json.path("versionId").asString());
            var version = assets.version(versionId); assertFalse(version.isAutoIndex());
            assertEquals(owner, assets.asset(version.getAssetId()).ownerId());
            try (var in = assets.open(versionId)) { assertArrayEquals(ImageGenerationClientTest.PNG, in.readAllBytes()); }
            assertEquals(versionId, files.list(session.getId()).getFirst().versionId());
            var lease = registry.claim(); assertNotNull(lease);
            var knowledge = mock(com.mindrails.backend.asset.service.AssetKnowledgeService.class);
            try (var worker = new com.mindrails.backend.asset.service.AssetWorker(registry, assets, knowledge, properties,
                    new com.mindrails.backend.knowledge.parser.DocumentParserRegistry(new com.mindrails.backend.knowledge.config.KnowledgeProperties()))) {
                worker.process(lease);
            }
            assertEquals("READY", assets.version(versionId).getState()); assertTrue(assets.blocks(versionId).isEmpty());
            verifyNoInteractions(knowledge);
            try (var other = com.mindrails.backend.identity.ResourceAccess.as(UUID.randomUUID())) {
                assertThrows(RuntimeException.class, () -> assets.version(versionId));
                assertThrows(RuntimeException.class, () -> files.list(session.getId()));
            }
        }
    }

    @Test void httpManagementRequiresAdminAndPersistsPublicTestResults() throws Exception {
        var identity = mock(com.mindrails.backend.identity.IdentityService.class);
        when(identity.authenticate("admin-token")).thenReturn(new com.mindrails.backend.identity.Identity.Current(
                com.mindrails.backend.identity.IdentityService.ADMIN_ID, "Admin", com.mindrails.backend.identity.Identity.Role.SystemAdmin, UUID.randomUUID()));
        when(identity.authenticate("normal-token")).thenReturn(new com.mindrails.backend.identity.Identity.Current(
                UUID.randomUUID(), "User", com.mindrails.backend.identity.Identity.Role.Normal, UUID.randomUUID()));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ImageProviderController(service))
                .setControllerAdvice(new ImageGenerationAdvice(), new com.mindrails.backend.identity.IdentityErrorAdvice())
                .addFilters(new com.mindrails.backend.identity.IdentityFilter(identity)).build();
        String body = new JsonMapper().writeValueAsString(input("http-provider", true, "http-private-key"));
        var create = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-generation-provider")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body);
        assertEquals(401, mvc.perform(create).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-generation-provider")
                .header("Authorization", "Bearer normal-token").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus());
        var response = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-generation-provider")
                .header("Authorization", "Bearer admin-token").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertEquals(201, response.getStatus()); assertFalse(response.getContentAsString().contains("http-private-key"));
        var id = new JsonMapper().readTree(response.getContentAsString()).path("id").asString();
        assertTrue(service.get(UUID.fromString(id)).testSuccess());
        var tested = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-generation-provider/" + id + "/test")
                .header("Authorization", "Bearer admin-token")).andReturn().getResponse();
        assertEquals(200, tested.getStatus()); assertTrue(new JsonMapper().readTree(tested.getContentAsString()).path("connected").asBoolean());
        assertEquals(2, service.tests(UUID.fromString(id)).size());
        var read = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/image-generation-provider")
                .header("Authorization", "Bearer normal-token")).andReturn().getResponse();
        assertEquals(200, read.getStatus()); assertFalse(read.getContentAsString().contains("http-private-key"));
        var invalid = body.replace("https://api.openai.com/v1/images/generations", "https://user:password@example.test/generate");
        var bad = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/image-generation-provider")
                .header("Authorization", "Bearer admin-token").contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(invalid)).andReturn().getResponse();
        assertEquals(400, bad.getStatus());
    }

    @Test void validationRejectsOtherVendorsInvalidKeysAndUnsafeEndpointShapes() {
        assertThrows(ImageGenerationException.class, () -> service.save(null, new ImageProvider.Input("bad", "other", "https://example.test", "key", "model", false, true, null, null, null)));
        assertThrows(ImageGenerationException.class, () -> service.save(null, input("bad", false, null)));
        assertThrows(ImageGenerationException.class, () -> service.save(null, new ImageProvider.Input("bad", "qwen", "http://public.example/images/generations", "key", "model", false, true, null, null, null)));
        assertThrows(ImageGenerationException.class, () -> service.save(null, new ImageProvider.Input("bad", "qwen", "https://user:password@example.test/images/generations", "key", "model", false, true, null, null, null)));
        assertTrue(service.list(0, 20).isEmpty()); verifyNoInteractions(remote);
    }
}
