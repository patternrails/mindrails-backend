package com.mindrails.backend.skill;

import dev.langchain4j.skills.FileSystemSkill;
import dev.langchain4j.skills.FileSystemSkillLoader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.WRITE;

final class SkillZip {
    private static final long MAX_EXPANDED_BYTES = 50L * 1024 * 1024;
    private static final int MAX_ENTRIES = 1_000;
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---[ \\t]*\\R([\\s\\S]*?)^---[ \\t]*$", Pattern.MULTILINE);
    private static final Pattern NAME_FIELD = Pattern.compile("^name:[^\\r\\n]*", Pattern.MULTILINE);
    private static final Pattern UNSAFE_NAME = Pattern.compile("[/\\\\:*?\"<>|\\p{Cntrl}]");

    private SkillZip() {}

    static Path extract(Path archive, Path staging) throws IOException {
        Path extracted = Files.createDirectory(staging.resolve("extracted"));
        try (var zip = new ZipFile(archive.toFile())) {
            if (zip.size() > MAX_ENTRIES) throw invalid("Skill ZIP contains too many entries");
            long remaining = MAX_EXPANDED_BYTES;
            var entries = zip.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                Path output = entryPath(extracted, entry.getName());
                String top = extracted.relativize(output).getName(0).toString();
                if (top.equals("__MACOSX") || output.getFileName().toString().equals(".DS_Store")) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                    continue;
                }
                remaining -= extractFile(zip, entry, output, buffer, remaining);
            }
        } catch (ZipException error) {
            throw invalid("Upload a valid skill ZIP");
        } catch (FileAlreadyExistsException error) {
            throw invalid("Skill ZIP contains conflicting paths");
        }
        return skillDirectory(extracted);
    }

    private static long extractFile(ZipFile zip, ZipEntry entry, Path output, byte[] buffer, long limit)
            throws IOException {
        if (entry.getSize() > limit) throw invalid("Expanded skill ZIP must be at most 50 MB");
        Files.createDirectories(output.getParent());
        long bytes = 0;
        var crc = new CRC32();
        try (var input = zip.getInputStream(entry); var file = Files.newOutputStream(output, CREATE_NEW, WRITE)) {
            for (int count; (count = input.read(buffer)) != -1; ) {
                bytes += count;
                if (bytes > limit) throw invalid("Expanded skill ZIP must be at most 50 MB");
                crc.update(buffer, 0, count);
                file.write(buffer, 0, count);
            }
        }
        if (bytes != entry.getSize() || crc.getValue() != entry.getCrc()) {
            throw new ZipException("Invalid ZIP entry");
        }
        return bytes;
    }

    private static Path skillDirectory(Path extracted) throws IOException {
        try (var files = Files.walk(extracted)) {
            var definitions = files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("SKILL.md")).limit(2).toList();
            if (definitions.size() != 1) throw invalid("Skill ZIP must contain exactly one SKILL.md");
            Path directory = definitions.getFirst().getParent();
            if (extracted.relativize(directory).getNameCount() > 1) {
                throw invalid("Place SKILL.md at the ZIP root or inside one top-level skill directory");
            }
            if (!directory.equals(extracted)) {
                try (var children = Files.list(extracted)) {
                    if (children.anyMatch(path -> !path.equals(directory))) {
                        throw invalid("Place all skill files in the same top-level directory");
                    }
                }
            }
            return directory;
        }
    }

    static FileSystemSkill verify(Path directory) throws IOException {
        String markdown = Files.readString(directory.resolve("SKILL.md"));
        var frontMatter = FRONT_MATTER.matcher(markdown);
        if (!frontMatter.find() || !NAME_FIELD.matcher(frontMatter.group(1)).find()) {
            throw invalid("SKILL.md must have YAML front matter with a name and description");
        }
        try {
            var options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setNestingDepthLimit(20);
            Object yaml = new Yaml(new SafeConstructor(options)).load(frontMatter.group(1));
            if (!(yaml instanceof Map<?, ?> fields) || !(fields.get("name") instanceof String name)
                    || !(fields.get("description") instanceof String description) || description.isBlank()) {
                throw invalid("SKILL.md must have a text name and description");
            }
            validateName(name);
            var skill = FileSystemSkillLoader.loadSkill(directory);
            if (!name.equals(skill.name()) || skill.description().isBlank() || skill.content().isBlank()) {
                throw invalid("SKILL.md must have a plain name, a description, and instructions");
            }
            return skill;
        } catch (YAMLException error) {
            throw invalid("SKILL.md contains invalid YAML front matter");
        }
    }

    static void rename(Path directory, String name) throws IOException {
        Path definition = directory.resolve("SKILL.md");
        String markdown = Files.readString(definition);
        var header = FRONT_MATTER.matcher(markdown);
        if (!header.find()) throw invalid("SKILL.md is missing YAML front matter");
        var field = NAME_FIELD.matcher(header.group(1));
        if (!field.find()) throw invalid("SKILL.md is missing a name");
        int start = header.start(1) + field.start();
        int end = header.start(1) + field.end();
        String nameLine = "name: '" + name.replace("'", "''") + "'";
        Files.writeString(definition, markdown.substring(0, start) + nameLine + markdown.substring(end));
    }

    static void validateName(String name) {
        if (name.isBlank() || !name.equals(name.strip()) || name.startsWith(".") || name.endsWith(".")
                || UNSAFE_NAME.matcher(name).find() || name.getBytes(StandardCharsets.UTF_8).length > 255) {
            throw invalid("Invalid skill name in SKILL.md");
        }
    }

    private static Path entryPath(Path root, String name) {
        if (name.isBlank() || name.startsWith("/") || name.contains("\\") || name.contains(":")
                || name.indexOf('\0') >= 0 || List.of(name.split("/")).contains("..")) {
            throw invalid("Skill ZIP contains an unsafe path");
        }
        Path output = root.resolve(name).normalize();
        if (output.equals(root) || !output.startsWith(root)) throw invalid("Skill ZIP contains an unsafe path");
        return output;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
