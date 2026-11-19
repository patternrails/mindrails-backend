package com.mindrails.backend.skill;

import com.mindrails.backend.identity.IdentityException;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.Skills;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Component
public class SkillCatalog {
    private final SkillFactory factory;

    public SkillCatalog(SkillFactory factory) {
        this.factory = factory;
    }

    public static UUID id(UUID userId, String name) {
        String key = "mindrails:skill:" + userId + ":" + name;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    public Path directory(UUID userId) {
        return factory.directory(userId);
    }

    public List<FileSystemSkill> definitions(UUID userId) {
        return factory.definitions(directory(userId));
    }

    public @Nullable Skills forSession(UUID userId) {
        return factory.loadSkills(directory(userId));
    }

    public @Nullable Skills forTask(UUID userId, List<UUID> ids) {
        if (ids.isEmpty()) return null;
        var selected = selected(userId, ids);
        return selected.isEmpty() ? null : Skills.from(selected);
    }

    public void validateIds(UUID userId, List<UUID> ids) {
        if (!ids.isEmpty() && selected(userId, ids).size() != Set.copyOf(ids).size()) {
            throw new IdentityException(404, "Skill not found");
        }
    }

    private List<FileSystemSkill> selected(UUID userId, List<UUID> ids) {
        var requested = Set.copyOf(ids);
        return definitions(userId).stream().filter(skill -> requested.contains(id(userId, skill.name()))).toList();
    }
}
