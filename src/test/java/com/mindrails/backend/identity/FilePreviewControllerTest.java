package com.mindrails.backend.identity;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.asset.api.web.*;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.AssetBlobStore;
import com.mindrails.backend.identity.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.UUID;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class FilePreviewControllerTest {
    @TempDir Path root;
    MockMvc mvc;
    String admin, other;
    SessionDeliverables files;
    TaskExecutionDeliverables tasks;
    AssetStorageProperties properties;
    AssetBlobStore blobs;
    IdentityService identity;
    AssetVersion assetVersion;
    final UUID session = UUID.randomUUID(), otherSession = UUID.randomUUID(), execution = UUID.randomUUID(), version = UUID.randomUUID();

    @BeforeEach void setup() throws Exception {
        var auth = new IdentityProperties(); auth.setAdminPassword("secure-test-password-2026");
        identity = new IdentityService(new InMemoryIdentityStore(), auth);
        admin = identity.login("admin", "secure-test-password-2026", "test").accessToken();
        var user = identity.create(identity.authenticate(admin), new UserController.UserWrite("alice", "Alice", "", "",
                "secure-test-password-2026", null, Identity.Role.Normal));
        other = identity.login("alice", "secure-test-password-2026", "test").accessToken();
        properties = new AssetStorageProperties(new FileStorageProperties(root));
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        registry.insertIfAbsent(Session.builder().id(session).build());
        registry.insertIfAbsent(Session.builder().id(otherSession).ownerId(user.id()).build());
        files = new SessionDeliverables(properties, registry);
        var executions = new InMemoryTaskExecutionStore();
        executions.insert(TaskExecution.builder().id(execution).ownerId(IdentityService.ADMIN_ID).createdAt(Instant.now()).build());
        tasks = new TaskExecutionDeliverables(properties, executions);
        var assets = mock(AssetRegistry.class); blobs = mock(AssetBlobStore.class);
        UUID asset = UUID.randomUUID();
        assetVersion = AssetVersion.builder().id(version).assetId(asset).filename("source.json")
                .blobKey("test/source.json").storageBackend("local").storageLocation(properties.location()).build();
        when(assets.version(version)).thenReturn(assetVersion);
        when(assets.asset(asset)).thenReturn(new Asset(asset, IdentityService.ADMIN_ID, "Source", "FILE", Instant.now()));
        when(blobs.open("test/source.json")).thenAnswer(call -> new ByteArrayInputStream("{\"large\":9007199254740993}".getBytes()));
        var storage = new DefaultAssetStorageService(assets, blobs, properties, new JsonMapper());
        var hosted = new HostedFileAccess(identity, auth);
        mvc = MockMvcBuilders.standaloneSetup(new FilePreviewController(storage, files, tasks, new FilePreviewService(properties), hosted))
                .setControllerAdvice(new AssetExceptionAdvice(), new AssetErrorAdvice(), new IdentityErrorAdvice())
                .addFilters(new IdentityFilter(identity, hosted)).build();
    }

    private UUID publish(String name, String text) throws Exception {
        Path path = files.resolve(session, name); Files.createDirectories(path.getParent()); Files.writeString(path, text);
        return files.publish(session, path).id();
    }

    @Test void previewRequiresBearerAndOwnerBeforeReadingAllResourceKinds() throws Exception {
        UUID file = publish("report.md", "# Report");
        Path taskPath = tasks.resolve(execution, "report.csv"); Files.createDirectories(taskPath.getParent()); Files.writeString(taskPath, "a,b\n1,2");
        UUID taskFile = tasks.publish(execution, taskPath).id();
        String assetPath = "/api/asset-storage/versions/" + version + "/preview";
        String sessionPath = "/api/asset-storage/sessions/" + session + "/deliverables/" + file + "/preview";
        String taskUrl = "/api/asset-storage/task-executions/" + execution + "/deliverables/" + taskFile + "/preview";
        for (String path : new String[]{assetPath, sessionPath, taskUrl}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer " + other)).andExpect(status().isNotFound());
        }
        verify(blobs, never()).open(anyString());
        for (String path : new String[]{assetPath, sessionPath, taskUrl})
            mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith("application/json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        mvc.perform(get(sessionPath.replace(session.toString(), otherSession.toString())).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
        mvc.perform(get(assetPath).header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.content").value("{\"large\":9007199254740993}"));
    }

    @Test void oversizedUnsupportedAndDeletedFilesHaveExplicitStatuses() throws Exception {
        UUID file = publish("report.txt", "hello");
        String path = "/api/asset-storage/sessions/" + session + "/deliverables/" + file + "/preview";
        properties.setMaxPreviewBytes(4);
        mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().is(413));
        properties.setMaxPreviewBytes(100);
        Files.write(files.download(session, file), new byte[]{0,1});
        mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().is(415));
        files.delete(session, file);
        mvc.perform(get(path).header("Authorization", "Bearer " + admin)).andExpect(status().isNotFound());
    }

    @Test void htmlIsServedAsATopLevelDocumentWithFileScopedCookiesForAllResourceKinds() throws Exception {
        String html = "<!doctype html><title>Hosted report</title><h1>Report</h1><button id='next' onclick='next()' data-slide='2'>Next</button><script>function next(){document.querySelector('h1').textContent='Slide 2'}</script>";
        assetVersion.setFilename("source.html");
        when(blobs.open("test/source.json")).thenAnswer(call -> new ByteArrayInputStream(html.getBytes()));
        UUID file = publish("report.html", html);
        Path taskPath = tasks.resolve(execution, "report.html"); Files.createDirectories(taskPath.getParent()); Files.writeString(taskPath, html);
        UUID taskFile = tasks.publish(execution, taskPath).id();
        String[] resources = {
                "/api/asset-storage/versions/" + version,
                "/api/asset-storage/sessions/" + session + "/deliverables/" + file,
                "/api/asset-storage/task-executions/" + execution + "/deliverables/" + taskFile};
        for (String resource : resources) {
            mvc.perform(post(resource + "/host").contentType("application/json").content("{\"origin\":\"http://localhost:3000\"}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(resource + "/host").header("Authorization", "Bearer " + other)
                    .contentType("application/json").content("{\"origin\":\"http://localhost:3000\"}"))
                    .andExpect(status().isNotFound());
            var result = mvc.perform(post(resource + "/host").header("Authorization", "Bearer " + admin)
                    .contentType("application/json").content("{\"origin\":\"http://localhost:3000\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.path").value(resource + "/view?app=http%3A%2F%2Flocalhost%3A3000"))
                    .andReturn();
            String header = result.getResponse().getHeader("Set-Cookie");
            org.junit.jupiter.api.Assertions.assertTrue(header.contains("HttpOnly"));
            org.junit.jupiter.api.Assertions.assertTrue(header.contains("Secure"));
            org.junit.jupiter.api.Assertions.assertTrue(header.contains("Path=" + resource + "/view"));
            String value = header.substring(header.indexOf('=') + 1, header.indexOf(';'));
            var cookie = new jakarta.servlet.http.Cookie(HostedFileAccess.COOKIE, value);
            for (int i = 0; i < 2; i++) mvc.perform(get(resource + "/view").cookie(cookie)).andExpect(status().isOk())
                    .andExpect(content().contentType("text/html;charset=UTF-8"))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<h1>Report</h1>")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("<title>Hosted report</title>")))
                    .andExpect(content().string(html))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("sandbox allow-scripts;")))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("script-src 'unsafe-inline'")))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("allow-same-origin"))))
                    .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("connect-src 'none'")))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(get("/api/auth/me").cookie(cookie)).andExpect(status().isUnauthorized());
            mvc.perform(get(resource + "/download").cookie(cookie)).andExpect(status().isUnauthorized());
            mvc.perform(get(resources[(java.util.Arrays.asList(resources).indexOf(resource) + 1) % 3] + "/view").cookie(cookie))
                    .andExpect(status().isUnauthorized());
            mvc.perform(get(resource + "/view")).andExpect(status().isUnauthorized());
            mvc.perform(get(resource + "/view").param("app", "http://localhost:3000"))
                    .andExpect(status().isSeeOther()).andExpect(header().string("Location", resource.replace("/api/asset-storage/versions/", "http://localhost:3000/files/assets/")
                            .replace("/api/asset-storage/", "http://localhost:3000/files/")));
        }
    }

    @Test void hostingRejectsOtherFormatsUntrustedRedirectsAndRevokedLoginSessions() throws Exception {
        UUID file = publish("report.html", "<h1>Report</h1>");
        String resource = "/api/asset-storage/sessions/" + session + "/deliverables/" + file;
        mvc.perform(post("/api/asset-storage/versions/" + version + "/host").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"origin\":\"http://localhost:3000\"}"))
                .andExpect(status().is(415)).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(post(resource + "/host").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"origin\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest()).andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get(resource + "/view?app=https://evil.example")).andExpect(status().isUnauthorized())
                .andExpect(header().doesNotExist("Location"));
        String header = mvc.perform(post(resource + "/host").header("Authorization", "Bearer " + admin)
                .contentType("application/json").content("{\"origin\":\"http://localhost:3000\"}"))
                .andReturn().getResponse().getHeader("Set-Cookie");
        var cookie = new jakarta.servlet.http.Cookie(HostedFileAccess.COOKIE, header.substring(header.indexOf('=') + 1, header.indexOf(';')));
        identity.logout(identity.authenticate(admin));
        mvc.perform(get(resource + "/view").cookie(cookie)).andExpect(status().isUnauthorized());
    }
}
