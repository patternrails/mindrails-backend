package com.mindrails.backend.skill.api;

import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.skill.SkillArchives;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SkillUploadTest {
    private static final String DEFINITION = "---\nname: example\ndescription: Example\n---\n\nInstructions";

    @TempDir Path root;
    private final UUID user = UUID.randomUUID();
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private SkillCatalog catalog;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        var factory = new SkillFactory(new FileStorageProperties(root));
        catalog = new SkillCatalog(factory);
        mvc = MockMvcBuilders.standaloneSetup(new SkillController(catalog, new SkillArchives(factory, jdbc)))
                .setControllerAdvice(new ServiceResponseAdvice()).build();
    }

    @Test
    void versionsSkillsPerUserAndRegistersTheirIds() throws Exception {
        var other = UUID.randomUUID();
        var wrapped = zip(Map.of("source/SKILL.md", DEFINITION, "source/references/guide.md", "Guide"));
        var flat = zip(Map.of("SKILL.md", DEFINITION));

        try (var scope = ResourceAccess.as(user)) {
            upload("wrapped.zip", wrapped, user, "example");
            upload("flat.ZIP", flat, user, "example-1");
            upload("wrapped.zip", wrapped, user, "example-2");
            mvc.perform(get("/api/skills/example-2/detail")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.content").value("Instructions"))
                    .andExpect(jsonPath("$.resources[0]").value("references/guide.md"));
        }
        try (var scope = ResourceAccess.as(other)) {
            mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
            upload("wrapped.zip", wrapped, other, "example");
        }

        assertTrue(catalog.forSession(user).formatAvailableSkills().contains("<name>example-2</name>"));
        assertFalse(catalog.forSession(other).formatAvailableSkills().contains("<name>example-2</name>"));
        for (String name : List.of("example", "example-1", "example-2")) {
            verify(jdbc).update(anyString(), eq(SkillCatalog.id(user, name).toString()), eq(user.toString()), eq(name));
        }
        verify(jdbc).update(anyString(), eq(SkillCatalog.id(other, "example").toString()),
                eq(other.toString()), eq("example"));
        try (var entries = Files.list(root.resolve("skill").resolve(user.toString()))) {
            assertFalse(entries.anyMatch(path -> path.getFileName().toString().startsWith(".upload-")));
        }
    }

    @Test
    void rejectsInvalidArchivesBeforeRegistration() throws Exception {
        try (var scope = ResourceAccess.as(user)) {
            reject("skill.md", zip(Map.of("SKILL.md", DEFINITION)));
            reject("skill.zip", "fake".getBytes(StandardCharsets.UTF_8));
            reject("skill.zip", zip(Map.of("SKILL.md", DEFINITION, "../outside.md", "escaped")));
            reject("skill.zip", zip(Map.of("one/SKILL.md", DEFINITION, "two/SKILL.md", DEFINITION)));
            reject("skill.zip", zip(Map.of("SKILL.md", "No front matter")));
            mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
        assertFalse(Files.exists(root.resolve("outside.md")));
        verifyNoInteractions(jdbc);
    }

    @Test
    void rollsBackTheInstalledDirectoryIfRegistrationFails() throws Exception {
        when(jdbc.update(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("Database unavailable"));

        try (var scope = ResourceAccess.as(user)) {
            mvc.perform(multipart("/api/skills/upload").file(part("skill.zip", zip(Map.of("SKILL.md", DEFINITION)))))
                    .andExpect(status().isServiceUnavailable());
            mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        }
        assertFalse(Files.exists(root.resolve("skill").resolve(user.toString()).resolve("example")));
    }

    private void upload(String filename, byte[] bytes, UUID owner, String name) throws Exception {
        mvc.perform(multipart("/api/skills/upload").file(part(filename, bytes)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(SkillCatalog.id(owner, name).toString()))
                .andExpect(jsonPath("$.ownerId").value(owner.toString()))
                .andExpect(jsonPath("$.name").value(name));
    }

    private void reject(String filename, byte[] bytes) throws Exception {
        mvc.perform(multipart("/api/skills/upload").file(part(filename, bytes))).andExpect(status().isBadRequest());
    }

    private static MockMultipartFile part(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/zip", bytes);
    }

    private static byte[] zip(Map<String, String> entries) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
