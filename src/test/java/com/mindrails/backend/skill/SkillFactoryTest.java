package com.mindrails.backend.skill;

import com.mindrails.backend.common.config.FileStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class SkillFactoryTest {
    @TempDir Path temp;
    private final UUID user = UUID.randomUUID();

    @Test
    void loadsOnlyTheRequestedUsersDirectoriesAcrossConcurrentCalls() throws Exception {
        var factory = factory(temp);
        var otherUser = UUID.randomUUID();
        skill(temp, user, "mine", "My instructions");
        skill(temp, otherUser, "theirs", "Their instructions");

        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = IntStream.range(0, 12).mapToObj(index -> workers.submit(() -> {
                UUID current = index % 2 == 0 ? user : otherUser;
                return factory.loadSkills(factory.directory(current)).formatAvailableSkills();
            })).toList();
            for (int index = 0; index < futures.size(); index++) {
                String available = futures.get(index).get(5, TimeUnit.SECONDS);
                assertTrue(available.contains(index % 2 == 0 ? "<name>mine</name>" : "<name>theirs</name>"));
                assertFalse(available.contains(index % 2 == 0 ? "<name>theirs</name>" : "<name>mine</name>"));
            }
        }
    }

    @Test
    void discoversAddedAndDeletedSkillsForANewUser() throws Exception {
        var factory = factory(temp);
        var userDirectory = factory.directory(user);
        assertNull(factory.loadSkills(userDirectory));

        var file = skill(temp, user, "example", "Instructions");
        assertEquals("example", factory.definitions(userDirectory).getFirst().name());
        assertNotNull(factory.loadSkills(userDirectory));

        Files.delete(file);
        assertNull(factory.loadSkills(userDirectory));
    }

    @Test
    void readsSameSizeInstructionEditsEvenWhenTheTimestampDoesNotChange() throws Exception {
        var file = skill(temp, user, "example", "OLD");
        var factory = factory(temp);
        var userDirectory = factory.directory(user);
        FileTime modified = Files.getLastModifiedTime(file);

        skill(temp, user, "example", "NEW");
        Files.setLastModifiedTime(file, modified);

        assertEquals("NEW", factory.definitions(userDirectory).getFirst().content());
    }

    @Test
    void reloadsResourcesAndIgnoresScripts() throws Exception {
        var directory = skill(temp, user, "example", "Instructions").getParent();
        var factory = factory(temp);
        var userDirectory = factory.directory(user);
        Files.createDirectories(directory.resolve("scripts"));
        Files.writeString(directory.resolve("scripts/run.sh"), "echo hello");
        assertTrue(factory.definitions(userDirectory).getFirst().resources().isEmpty());

        var reference = directory.resolve("references/nested/info.md");
        Files.createDirectories(reference.getParent());
        Files.writeString(reference, "First reference");
        assertEquals("First reference", factory.definitions(userDirectory).getFirst().resources().getFirst().content());

        Files.writeString(reference, "Updated reference");
        assertEquals("Updated reference", factory.definitions(userDirectory).getFirst().resources().getFirst().content());

        Files.delete(reference);
        assertTrue(factory.definitions(userDirectory).getFirst().resources().isEmpty());
    }

    @Test
    void ignoresLegacyDirectoriesAndLoadsOnlyTheSingleConfiguredUserPath() throws Exception {
        var factory = factory(temp);
        var legacy = Files.createDirectories(temp.resolve("skills/users").resolve(user.toString()).resolve("example"));
        Files.writeString(legacy.resolve("SKILL.md"), "---\nname: example\ndescription: Legacy\n---\nLegacy instructions");
        assertNull(factory.loadSkills(factory.directory(user)));

        skill(temp, user, "example", "Current instructions");
        assertEquals("Current instructions", factory.definitions(factory.directory(user)).getFirst().content());
    }

    @Test
    void invalidDefinitionsAreReportedAndRetriedAfterRepair() throws Exception {
        var file = skill(temp, user, "example", "Valid instructions");
        var factory = factory(temp);
        var userDirectory = factory.directory(user);
        Files.writeString(file, "Invalid skill without front matter");
        assertThrows(RuntimeException.class, () -> factory.loadSkills(userDirectory));

        skill(temp, user, "example", "Repaired instructions");
        assertEquals("Repaired instructions", factory.definitions(userDirectory).getFirst().content());
    }

    @Test
    void skipsSymlinkedUserDirectoriesAndSkillResources() throws Exception {
        var other = UUID.randomUUID();
        var foreignSkill = skill(temp, other, "example", "Other user's instructions");
        var factory = factory(temp);
        var directory = factory.directory(user);
        Files.createSymbolicLink(directory, foreignSkill.getParent().getParent());
        assertNull(factory.loadSkills(factory.directory(user)));

        Files.delete(directory);
        var mySkill = skill(temp, user, "my-skill", "My instructions");
        Files.createSymbolicLink(mySkill.getParent().resolve("reference.md"), foreignSkill);
        assertNull(factory.loadSkills(factory.directory(user)));
    }

    private static SkillFactory factory(Path root) {
        return new SkillFactory(new FileStorageProperties(root));
    }

    private static Path skill(Path root, UUID user, String name, String instructions) throws Exception {
        var directory = Files.createDirectories(factory(root).directory(user).resolve(name));
        return Files.writeString(directory.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: Use for " + name + " tasks\n---\n" + instructions);
    }
}
