package com.mindrails.backend.tool.spec;

import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.tool.ToolFailure;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;

class FileToolsTest {

    @TempDir Path directory;

    @Test
    void scopedFileToolsCanModifyOnlyTheirOwnSkillDirectory() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Path own = directory.resolve("skill").resolve(owner.toString()).resolve("example/SKILL.md");
        Path foreign = directory.resolve("skill").resolve(other.toString()).resolve("example/SKILL.md");
        var writer = new WriteTool(directory);
        var editor = new EditTool(directory);
        writer.write(foreign.toString(), "other account");

        try (var scope = ResourceAccess.as(owner)) {
            writer.write(own.toString(), "first");
            editor.edit(own.toString(), List.of(new EditTool.Replacement("first", "updated")));
            assertThrows(ToolFailure.class, () -> writer.write(foreign.toString(), "overwritten"));
            assertThrows(ToolFailure.class, () -> editor.edit(foreign.toString(),
                    List.of(new EditTool.Replacement("other account", "overwritten"))));
        }

        assertEquals("updated", Files.readString(own));
        assertEquals("other account", Files.readString(foreign));
    }

    @Test
    void writesUtf8CreatesParentsAndOverwritesRatherThanAppending() throws Exception {
        Path file = directory.resolve("nested/text.txt");
        WriteTool tool = new WriteTool(directory);
        var result = tool.write(file.toString(), "你好\nsecond\nthird\n");
        assertEquals(Files.size(file), result.bytesWritten());
        var read = read(file, 2, 1);
        assertEquals("second", read.content());
        assertEquals(3, read.totalLines());
        assertEquals(1, read.returnedLines());
        assertEquals(3L, read.nextOffset());
        tool.write(file.toString(), "short");
        assertEquals("short", Files.readString(file));
        tool.write(file.toString(), "");
        assertEquals(0, Files.size(file));
        assertEquals(0, read(file, null, null).totalLines());
        assertThrows(IllegalArgumentException.class, () -> tool.write(file.toString(), null));
        assertThrows(IllegalArgumentException.class, () -> new ReadTool().read(file.toString(), 2, null));
    }

    @Test
    void writesAndEditsStayInsideTheConfiguredRoot() throws Exception {
        Path root = Files.createDirectory(directory.resolve("data"));
        Path outside = Files.writeString(directory.resolve("outside.txt"), "original");
        Path link = Files.createSymbolicLink(root.resolve("link.txt"), outside);
        var writer = new WriteTool(root);
        var editor = new EditTool(root);
        var replacements = List.of(new EditTool.Replacement("original", "changed"));
        assertThrows(ToolFailure.class, () -> writer.write("../outside.txt", "changed"));
        assertThrows(ToolFailure.class, () -> writer.write(link.toString(), "changed"));
        assertThrows(ToolFailure.class, () -> editor.edit(outside.toString(), replacements));
        assertThrows(ToolFailure.class, () -> editor.edit(link.toString(), replacements));
        assertEquals("original", Files.readString(outside));
        writer.write("shared/note.txt", "original");
        editor.edit(root.resolve("shared/note.txt").toString(), replacements);
        assertEquals("changed", Files.readString(root.resolve("shared/note.txt")));
    }

    @Test
    void readProvidesContinuationAtByteAndLineLimits() throws Exception {
        Path file = directory.resolve("large.txt");
        Files.writeString(file, ("中".repeat(10_000) + "\n").repeat(2));
        var result = read(file, null, null);
        assertTrue(result.byteLimitReached());
        assertEquals(1, result.returnedLines());
        assertEquals(2L, result.nextOffset());
        assertEquals(2, result.totalLines());
        assertNull(read(file, 2, null).nextOffset());

        Files.writeString(file, "line\n".repeat(2001));
        assertEquals(2000, read(file, null, null).returnedLines());
        assertEquals(2001L, read(file, null, null).nextOffset());
        Files.writeString(file, "中".repeat(20_000));
        assertThrows(java.io.IOException.class, () -> read(file, null, null));
    }

    @Test
    void imagesReachLc4jAsMultimodalContent() throws Exception {
        Path image = directory.resolve("pixel.bin");
        byte[] png = Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aWQAAAABJRU5ErkJggg==");
        Files.write(image, png);
        var method = ReadTool.class.getMethod("read", String.class, Integer.class, Integer.class);
        var executor = DefaultToolExecutor.builder().object(new ReadTool())
                .originalMethod(method).methodToInvoke(method).build();
        var request = ToolExecutionRequest.builder().id("image-read").name("read")
                .arguments(ToolSupport.json(Map.of("path", image.toString()))).build();
        var result = executor.executeWithContext(request, InvocationContext.builder().build());

        assertEquals(2, result.resultContents().size());
        var attachment = assertInstanceOf(ImageContent.class, result.resultContents().get(1));
        assertEquals("image/png", attachment.image().mimeType());
        assertArrayEquals(png, Base64.getDecoder().decode(attachment.image().base64Data()));
    }

    @Test
    void editsUseTheOriginalFileAndPreserveBomAndCrLf() throws Exception {
        Path file = directory.resolve("edit.txt");
        Files.writeString(file, "\uFEFFalpha\r\nbeta\r\ngamma\r\n");
        var result = new EditTool(directory).edit(file.toString(), List.of(
                new EditTool.Replacement("alpha", "beta"),
                new EditTool.Replacement("beta\ngamma", "delta")));

        assertEquals("\uFEFFbeta\r\ndelta\r\n", Files.readString(file));
        assertEquals(2, result.replacements());
        assertEquals(1, result.firstChangedLine());
    }

    @Test
    void invalidEditsLeaveTheOriginalFileUntouched() throws Exception {
        Path file = directory.resolve("unchanged.txt");
        String original = "alpha beta alpha\n";
        Files.writeString(file, original);
        EditTool tool = new EditTool(directory);
        for (List<EditTool.Replacement> edits : List.of(
                List.of(new EditTool.Replacement("alpha", "x")),
                List.of(new EditTool.Replacement("beta", "x"), new EditTool.Replacement("missing", "y")),
                List.of(new EditTool.Replacement("alpha beta", "x"), new EditTool.Replacement("beta alpha", "y")),
                List.of(new EditTool.Replacement("beta", "beta")),
                List.of(new EditTool.Replacement("", "x")))) {
            assertThrows(IllegalArgumentException.class, () -> tool.edit(file.toString(), edits));
            assertEquals(original, Files.readString(file));
        }
    }

    @Test
    void lc4jMapsReplacementRecordsFromJson() throws Exception {
        Path file = directory.resolve("mapped.txt");
        Files.writeString(file, "before");
        var method = EditTool.class.getMethod("edit", String.class, List.class);
        var executor = DefaultToolExecutor.builder().object(new EditTool(directory))
                .originalMethod(method).methodToInvoke(method).build();
        var request = ToolExecutionRequest.builder().id("edit").name("edit")
                .arguments(ToolSupport.json(Map.of("path", file.toString(), "edits",
                        List.of(Map.of("oldText", "before", "newText", "after"))))).build();
        var result = executor.executeWithContext(request, InvocationContext.builder().build());

        assertEquals("after", Files.readString(file));
        assertFalse(result.isError());
        assertTrue(result.resultText().contains("replacements"));
    }

    @Test
    void concurrentEditsDoNotLoseChangesAndConcurrentWritesDoNotInterleave() throws Exception {
        Path file = directory.resolve("concurrent.txt");
        List<String> blocks = new ArrayList<>();
        List<Callable<EditTool.Result>> edits = new ArrayList<>();
        EditTool editor = new EditTool(directory);
        for (int i = 0; i < 24; i++) {
            String before = "[old-" + i + "]";
            String after = "[new-" + i + "]";
            blocks.add(before);
            edits.add(() -> editor.edit(file.toString(), List.of(new EditTool.Replacement(before, after))));
        }
        Files.writeString(file, String.join("\n", blocks));
        try (var executor = Executors.newFixedThreadPool(8)) {
            for (var future : executor.invokeAll(edits, 10, TimeUnit.SECONDS)) future.get();
            assertEquals(String.join("\n", blocks).replace("old-", "new-"), Files.readString(file));

            WriteTool writer = new WriteTool(directory);
            String first = "甲".repeat(100_000);
            String second = "乙".repeat(50_000);
            List<Callable<WriteTool.Result>> writes = List.of(
                    () -> writer.write(file.toString(), first), () -> writer.write(file.toString(), second));
            for (var future : executor.invokeAll(writes, 10, TimeUnit.SECONDS)) future.get();
            assertTrue(List.of(first, second).contains(Files.readString(file, StandardCharsets.UTF_8)));
        }
    }

    private ReadTool.TextResult read(Path path, Integer offset, Integer limit) throws Exception {
        var content = new ReadTool().read(path.toString(), offset, limit);
        return new JsonMapper().readValue(((TextContent) content.getFirst()).text(), ReadTool.TextResult.class);
    }
}
