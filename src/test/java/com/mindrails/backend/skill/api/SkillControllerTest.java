package com.mindrails.backend.skill.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.common.ServiceResponseAdvice;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.identity.IdentityService;
import com.mindrails.backend.skill.SkillArchives;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SkillControllerTest {
    @TempDir Path temp;

    @Test
    void searchesAndPaginatesInStableOrderWithoutSendingInstructions() throws Exception {
        skill(temp, "zebra", "Searchable description", "PRIVATE INSTRUCTIONS");
        skill(temp, "alpha", "Searchable description", "PRIVATE INSTRUCTIONS");
        var mvc = mvc(temp);
        mvc.perform(get("/api/skills").param("q", "SEARCHABLE").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("alpha"))
                .andExpect(jsonPath("$.items[0].root").value(userRoot(temp).resolve("alpha").toString()))
                .andExpect(jsonPath("$.items[0].content").doesNotExist())
                .andExpect(jsonPath("$.items[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.hasNext").value(true));
        mvc.perform(get("/api/skills").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("zebra"))
                .andExpect(jsonPath("$.hasNext").value(false));
        mvc.perform(get("/api/skills").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/skills").param("q", "missing"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void detailUsesNativeInstructionsAndReadableResourcesAndQueriesSeeFileChanges() throws Exception {
        var directory = skill(temp, "example", "Example skill", "FIRST INSTRUCTIONS");
        Files.createDirectories(directory.resolve("references"));
        Files.writeString(directory.resolve("references/info.md"), "Reference");
        Files.createDirectories(directory.resolve("scripts"));
        Files.writeString(directory.resolve("scripts/run.sh"), "echo hello");
        var mvc = mvc(temp);
        mvc.perform(get("/api/skills/example/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skill.name").value("example"))
                .andExpect(jsonPath("$.content").value("FIRST INSTRUCTIONS"))
                .andExpect(jsonPath("$.resources.length()").value(1))
                .andExpect(jsonPath("$.resources[0]").value("references/info.md"));
        skill(temp, "example", "Updated description", "UPDATED INSTRUCTIONS");
        mvc.perform(get("/api/skills/example/detail"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("UPDATED INSTRUCTIONS"));
        skill(temp, "new-skill", "New skill", "New instructions");
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/skills/missing/detail"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void configShowsTheUserDirectoryBeforeItExists() throws Exception {
        var mvc = mvc(temp.resolve("missing"));
        mvc.perform(get("/api/skills/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths[0]").value(userRoot(temp.resolve("missing")).toString()))
                .andExpect(jsonPath("$.classpathPath").value(""));
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/skills/example/detail")).andExpect(status().isNotFound());
    }

    @Test
    void invalidSkillFailsDiscoveryRatherThanReturningAnEmptyCatalog() throws Exception {
        Files.createDirectories(userRoot(temp).resolve("invalid"));
        Files.writeString(userRoot(temp).resolve("invalid/SKILL.md"), "No front matter");
        mvc(temp).perform(get("/api/skills"))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNAVAILABLE"));
    }

    @ParameterizedTest
    @CsvSource({"-1,20", "0,0", "0,101"})
    void validatesPagination(int page, int size) throws Exception {
        mvc(temp).perform(get("/api/skills").param("page", Integer.toString(page)).param("size", Integer.toString(size)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc(temp).perform(get("/api/skills").param("q", "x".repeat(1025)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletingASkillArchivesItAndTheNextReadSeesTheChange() throws Exception {
        skill(temp, "removable", "Removable skill", "Instructions");
        var mvc = mvc(temp);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/skills/removable"))
                .andExpect(status().isNoContent());
        try (var archived = Files.list(userRoot(temp).resolve(".deleted"))) {
            org.junit.jupiter.api.Assertions.assertTrue(archived.anyMatch(path -> Files.exists(path.resolve("SKILL.md"))));
        }
        mvc.perform(get("/api/skills")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/skills/removable"))
                .andExpect(status().isNotFound());
    }

    private MockMvc mvc(Path path) { return mvc(new SkillFactory(new FileStorageProperties(path))); }

    private MockMvc mvc(SkillFactory factory) {
        var archives = new SkillArchives(factory, org.mockito.Mockito.mock(JdbcTemplate.class));
        return MockMvcBuilders.standaloneSetup(new SkillController(new SkillCatalog(factory), archives))
                .setControllerAdvice(new ServiceResponseAdvice()).build();
    }

    private static Path userRoot(Path path) {
        return path.resolve("skill").resolve(IdentityService.ADMIN_ID.toString());
    }

    private Path skill(Path root, String name, String description, String instructions) throws Exception {
        var directory = userRoot(root).resolve(name);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("SKILL.md"), markdown(name, description, instructions));
        return directory;
    }
    private String markdown(String name, String description, String instructions) {
        return "---\nname: " + name + "\ndescription: " + description + "\n---\n\n" + instructions;
    }
}
