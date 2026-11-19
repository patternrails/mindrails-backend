package com.mindrails.backend.skill;

import com.mindrails.backend.common.config.FileStorageProperties;
import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import dev.langchain4j.skills.Skills;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

@Component
public class SkillFactory {
    private final FileStorageProperties files;
    private final Path root;

    public SkillFactory(FileStorageProperties files) {
        this.files = files;
        root = files.skills();
    }

    public Path directory(UUID userId) {
        return root.resolve(userId.toString());
    }

    public @Nullable Skills loadSkills(Path directory) {
        var definitions = definitions(directory);
        return definitions.isEmpty() ? null : Skills.from(definitions);
    }

    List<FileSystemSkill> definitions(Path directory) {
        var skills = new LinkedHashMap<String, FileSystemSkill>();
        for (FileSystemSkill skill : readDirectory(directory)) {
            skills.put(skill.name(), skill);
        }
        return List.copyOf(skills.values());
    }

    List<FileSystemSkill> readDirectory(Path directory) {
        if (!isSafeDirectory(directory) || !Files.isDirectory(directory, NOFOLLOW_LINKS)) return List.of();
        try (var entries = Files.list(directory)) {
            return entries.filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> Files.isDirectory(path, NOFOLLOW_LINKS))
                    .filter(path -> Files.isRegularFile(path.resolve("SKILL.md"), NOFOLLOW_LINKS))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .filter(SkillFactory::containsNoSymlinks)
                    .map(FileSystemSkillLoader::loadSkill)
                    .toList();
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read skill directory", error);
        }
    }

    private static boolean containsNoSymlinks(Path directory) {
        try (var files = Files.walk(directory)) {
            return files.noneMatch(Files::isSymbolicLink);
        } catch (IOException error) {
            throw new UncheckedIOException("Failed to read skill files", error);
        }
    }

    boolean isSafeDirectory(Path directory) {
        if (!directory.startsWith(root)) return false;
        for (Path part = directory; part != null && part.startsWith(files.directory()); part = part.getParent()) {
            if (Files.isSymbolicLink(part)) return false;
        }
        return true;
    }
}
