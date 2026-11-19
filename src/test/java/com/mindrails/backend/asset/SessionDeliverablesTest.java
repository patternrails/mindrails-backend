package com.mindrails.backend.asset;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.asset.api.web.SessionDeliverablesController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionDeliverablesTest {
    @TempDir Path root;
    final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    private SessionDeliverables files() {
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        sessions.insertIfAbsent(Session.builder().id(a).build());
        sessions.insertIfAbsent(Session.builder().id(b).build());
        var properties = new AssetStorageProperties(new FileStorageProperties(root));
        return new SessionDeliverables(properties, sessions);
    }

    private void publish(SessionDeliverables files, UUID session, String name, String content) throws Exception {
        Path path = files.resolve(session, name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        files.publish(session, path);
    }

    @Test void traversalSymlinksAndCrossSessionWritesAreRejected() throws Exception {
        var files = files();
        assertThrows(AssetException.class, () -> files.resolve(a, "../../escape"));
        assertThrows(AssetException.class, () -> files.resolve(a, files.resolve(b, "report.md").toString()));
        Path directory = files.resolve(a, "nested/report.md").getParent(); Files.createDirectories(directory);
        Files.createSymbolicLink(directory.resolve("escape"), root);
        assertThrows(AssetException.class, () -> files.resolve(a, "nested/escape/outside"));
        assertThrows(AssetException.class, () -> files.list(UUID.randomUUID()));
    }

    @Test void cleanupRemovesOutputsAndMetadataWithoutTouchingOtherSessions() throws Exception {
        var files = files();
        publish(files, a, "output.txt", "one");
        publish(files, b, "output.txt", "two");
        files.deleteSession(a);
        assertFalse(Files.exists(root.resolve("assets/sessions").resolve(a.toString())));
        assertTrue(files.list(a).isEmpty()); assertEquals(1, files.list(b).size());
        files.deleteSession(a);
    }

    @Test void deletingOneFilePreservesOtherOutputsAndSessionOwnership() throws Exception {
        var files = files();
        publish(files, a, "nested/remove.md", "remove");
        publish(files, a, "keep.md", "keep");
        publish(files, b, "other.md", "other");
        var removed = files.list(a).stream().filter(file -> file.name().equals("remove.md")).findFirst().orElseThrow();
        assertThrows(AssetException.class, () -> files.delete(b, removed.id()));
        files.delete(a, removed.id());
        assertFalse(Files.exists(files.resolve(a, removed.path())));
        assertFalse(Files.exists(root.resolve("assets/sessions").resolve(a.toString()).resolve("metadata").resolve(removed.id() + ".json")));
        assertEquals(List.of("keep.md"), files.list(a).stream().map(SessionDeliverables.File::name).toList());
        assertEquals("other", Files.readString(files.download(b, files.list(b).getFirst().id())));
        assertThrows(AssetException.class, () -> files.download(a, removed.id()));
        assertThrows(AssetException.class, () -> files.delete(a, removed.id()));
        publish(files, a, "nested/remove.md", "recreated");
        assertEquals("recreated", Files.readString(files.download(a, removed.id())));
    }

    @Test
    void publicationValidatesOwnershipAndSkipsPendingFiles() throws Exception {
        var files = files();
        Path output = files.resolve(a, "report.csv");
        Files.createDirectories(output.getParent());
        Files.writeString(output, "ready");
        Files.writeString(output.getParent().resolve(".pending-output.part"), "unfinished");
        assertThrows(AssetException.class, () -> files.publish(b, output));
        assertEquals(List.of("report.csv"), files.publishAll(a).stream()
                .map(SessionDeliverables.File::name).toList());
        assertEquals("ready", Files.readString(files.download(a, files.list(a).getFirst().id())));
        assertTrue(files.list(b).isEmpty());
    }
    @Test void httpListsDownloadsAndMissingSessionsUseTheAssetContract() throws Exception {
        var files = files();
        publish(files, a, "output.csv", "item,total\nTravel,120");
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SessionDeliverablesController(files))
                .setControllerAdvice(new com.mindrails.backend.asset.api.web.AssetExceptionAdvice(), new com.mindrails.backend.asset.api.web.AssetErrorAdvice()).build();
        String base = "/api/asset-storage/sessions/" + a + "/deliverables";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].name").value("output.csv"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/" + files.list(a).getFirst().id() + "/download"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("item,total\nTravel,120"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/asset-storage/sessions/" + b + "/deliverables/" + files.list(a).getFirst().id() + "/download"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        UUID id = files.list(a).getFirst().id();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/asset-storage/sessions/" + b + "/deliverables/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/" + id + "/download"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }
}
