package com.mindrails.backend.tool.spec;

import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LsToolTest {

    @TempDir
    Path directory;

    @Test
    void listsImmediateEntriesAndHonorsLimits() throws Exception {
        Files.createFile(directory.resolve(".hidden"));
        Files.createFile(directory.resolve("z.txt"));
        Path child = Files.createDirectory(directory.resolve("Alpha"));
        LsTool tool = new LsTool();

        assertEquals(new LsTool.Result(List.of(".hidden", "Alpha/", "z.txt"), 3, 1, 500),
                tool.list(directory.toString(), null, null));
        assertEquals(new LsTool.Result(List.of(".hidden", "Alpha/", "z.txt"), 3, 1, 3),
                tool.list(directory.toString(), 1, 3));
        assertEquals(new LsTool.Result(List.of(".hidden"), 3, 1, 1), tool.list(directory.toString(), null, 1));
        assertEquals(new LsTool.Result(List.of(), 0, 1, 500), tool.list(child.toString(), null, null));
        Files.createFile(child.resolve("nested.txt"));
        assertEquals(new LsTool.Result(List.of(".hidden", "Alpha/", "z.txt"), 3, 1, 500),
                tool.list(directory.toString(), null, null));
        assertThrows(IllegalArgumentException.class, () -> tool.list(directory.toString(), null, 0));
        assertThrows(IllegalArgumentException.class, () -> tool.list(directory.toString(), null, -1));
        assertThrows(IllegalArgumentException.class, () -> tool.list(directory.toString(), 0, null));
        assertThrows(IllegalArgumentException.class, () -> tool.list(directory.toString(), -1, null));
        assertThrows(NoSuchFileException.class, () -> tool.list(directory.resolve("missing").toString(), null, null));
        assertThrows(NotDirectoryException.class, () -> tool.list(directory.resolve("z.txt").toString(), null, null));
    }

    @Test
    void paginatesInStableOrderAndKeepsTheTotalOnEmptyPages() throws Exception {
        for (String name : List.of("c.txt", "A.txt", "b.txt")) {
            Files.createFile(directory.resolve(name));
        }
        LsTool tool = new LsTool();
        String path = directory.toString();

        assertEquals(new LsTool.Result(List.of("A.txt", "b.txt"), 3, 1, 2), tool.list(path, 1, 2));
        assertEquals(new LsTool.Result(List.of("c.txt"), 3, 2, 2), tool.list(path, 2, 2));
        assertEquals(new LsTool.Result(List.of(), 3, 3, 2), tool.list(path, 3, 2));
        assertEquals(new LsTool.Result(List.of(), 3, Integer.MAX_VALUE, Integer.MAX_VALUE),
                tool.list(path, Integer.MAX_VALUE, Integer.MAX_VALUE));
    }

    @Test
    void exposesOneToolWithRequiredPathAndOptionalPagination() {
        var specifications = ToolSpecifications.toolSpecificationsFrom(new LsTool());

        assertEquals(1, specifications.size());
        var specification = specifications.getFirst();
        assertEquals("ls", specification.name());
        assertFalse(specification.description().isBlank());
        var parameters = specification.parameters();
        assertEquals(3, parameters.properties().size());
        var path = assertInstanceOf(JsonStringSchema.class, parameters.properties().get("path"));
        var page = assertInstanceOf(JsonIntegerSchema.class, parameters.properties().get("page"));
        var limit = assertInstanceOf(JsonIntegerSchema.class, parameters.properties().get("limit"));
        assertFalse(path.description().isBlank());
        assertFalse(page.description().isBlank());
        assertFalse(limit.description().isBlank());
        assertEquals(List.of("path"), parameters.required());
    }

    @Test
    void supportsConcurrentCallsOnTheSameInstance() throws Exception {
        Path first = Files.createDirectory(directory.resolve("first"));
        Path second = Files.createDirectory(directory.resolve("second"));
        Files.createFile(first.resolve("a.txt"));
        Files.createFile(second.resolve("b.txt"));
        LsTool tool = new LsTool();
        List<Callable<LsTool.Result>> calls = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String path = (i % 2 == 0 ? first : second).toString();
            calls.add(() -> tool.list(path, null, null));
        }

        try (var executor = Executors.newFixedThreadPool(4)) {
            var results = executor.invokeAll(calls, 5, TimeUnit.SECONDS);
            for (int i = 0; i < results.size(); i++) {
                assertEquals(new LsTool.Result(List.of(i % 2 == 0 ? "a.txt" : "b.txt"), 1, 1, 500),
                        results.get(i).get());
            }
        }
    }

    @Test
    void resolvesRelativePathsFromTheApplicationWorkingDirectory() throws Exception {
        Files.createFile(directory.resolve("note.txt"));
        Path relative = Path.of("").toAbsolutePath().relativize(directory.toAbsolutePath());

        assertEquals(new LsTool.Result(List.of("note.txt"), 1, 1, 500),
                new LsTool().list(relative.toString(), null, null));
    }

    @Test
    void rejectsMissingAndBlankPaths() {
        LsTool tool = new LsTool();

        assertThrows(IllegalArgumentException.class, () -> tool.list(null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tool.list("", null, null));
        assertThrows(IllegalArgumentException.class, () -> tool.list("  ", null, null));
    }
}
