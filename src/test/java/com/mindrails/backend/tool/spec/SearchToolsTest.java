package com.mindrails.backend.tool.spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class SearchToolsTest {

    @TempDir Path directory;

    @BeforeEach
    void files() throws Exception {
        Files.writeString(directory.resolve(".gitignore"), "ignored/\n*.skip\n");
        Files.createDirectory(directory.resolve("ignored"));
        Files.writeString(directory.resolve("ignored/hidden.txt"), "needle");
        Files.writeString(directory.resolve("excluded.skip"), "needle");
        Files.createDirectory(directory.resolve(".git"));
        Files.writeString(directory.resolve(".git/config"), "needle");
        Files.createDirectory(directory.resolve("src"));
        Files.writeString(directory.resolve("src/b.txt"), "before\nneedle one\nmiddle\nNEEDLE two\nafter\n");
        Files.writeString(directory.resolve("src/a.txt"), "needle literal.*\n");
        Files.writeString(directory.resolve(".hidden.txt"), "needle hidden\n");
    }

    @Test
    void missingRgFallsBackAndNativeResultsKeepTheSamePagingAndContext() throws Exception {
        var java = new GrepTool(directory.resolve("missing-rg").toString());
        var nativeTool = new GrepTool();
        var fallback = java.grep("needle", directory.toString(), "*.txt", true, false, 1, 2, 2);
        assertEquals(fallback, nativeTool.grep("needle", directory.toString(), "*.txt", true, false, 1, 2, 2));
        // Lookbehind is valid Java but unsupported by rg's default Rust regex engine.
        assertEquals(java.grep("(?<=needle) one", directory.toString(), null, false, false, 0, 1, 10),
                nativeTool.grep("(?<=needle) one", directory.toString(), null, false, false, 0, 1, 10));
    }

    @Test
    void findsGlobMatchesWithIgnoreRulesHiddenFilesAndPagination() throws Exception {
        FindTool tool = new FindTool();
        var first = tool.find("*.txt", directory.toString(), 1, 2);
        assertEquals(List.of(".hidden.txt", "src/a.txt"), first.entries());
        assertEquals(3, first.total());
        assertEquals(List.of("src/b.txt"), tool.find("*.txt", directory.toString(), 2, 2).entries());
        assertEquals(3, tool.find("*.txt", directory.toString(), 3, 2).total());
        assertTrue(tool.find("*.txt", directory.toString(), 3, 2).entries().isEmpty());
        assertEquals(List.of("src/a.txt", "src/b.txt"), tool.find("src/**/*.txt", directory.toString(), null, null).entries());
        assertTrue(tool.find("*.none", directory.toString(), null, null).entries().isEmpty());
    }

    @Test
    void fileNamesWithNewlinesRemainSingleEntries() throws Exception {
        Files.writeString(directory.resolve("two\nlines.txt"), "text");
        assertTrue(new FindTool().find("*.txt", directory.toString(), null, null).entries().contains("two\nlines.txt"));
    }

    @Test
    void grepReturnsContextTotalAndStablePages() throws Exception {
        GrepTool tool = new GrepTool();
        var result = tool.grep("needle", directory.toString(), "*.txt", true, false, 1, 2, 2);
        assertEquals(4, result.total());
        assertEquals(List.of(2L, 4L), result.matches().stream().map(GrepTool.Match::line).toList());
        assertEquals("src/b.txt", result.matches().getFirst().path());
        assertEquals("before", result.matches().getFirst().before().getFirst().text());
        assertEquals("middle", result.matches().getFirst().after().getFirst().text());
        assertEquals("after", result.matches().getLast().after().getFirst().text());
        var withoutGlob = tool.grep("needle", directory.toString(), null, false, false, null, null, null);
        assertEquals(3, withoutGlob.total());
        assertTrue(tool.grep("needle", directory.toString(), null, false, false, 0, 100, 2).matches().isEmpty());
    }

    @Test
    void grepSupportsLiteralSingleFileAndNoMatches() throws Exception {
        GrepTool tool = new GrepTool();
        var result = tool.grep("literal.*", directory.resolve("src/a.txt").toString(), null, false, true, null, null, null);
        assertEquals(1, result.total());
        assertEquals("a.txt", result.matches().getFirst().path());
        assertEquals(0, tool.grep("not found", directory.toString(), null, false, true, null, null, null).total());
        assertThrows(IOException.class, () -> tool.grep("[", directory.toString(), null, null, null, null, null, null));
    }

    @Test
    void grepMarksLongLinesAsTruncated() throws Exception {
        Files.writeString(directory.resolve("long.txt"), "needle" + "x".repeat(600));
        var result = new GrepTool().grep("needle", directory.resolve("long.txt").toString(), null, null, null, null, null, null);
        assertTrue(result.matches().getFirst().truncated());
        assertTrue(result.matches().getFirst().text().length() <= 501);
    }

    @Test
    void nestedIgnoreRulesAndNegationApplyToBothSearchTools() throws Exception {
        Path nested = Files.createDirectory(directory.resolve("nested"));
        Files.writeString(nested.resolve(".gitignore"), "*.tmp\n!keep.tmp\n");
        Files.writeString(nested.resolve("keep.tmp"), "needle");
        Files.writeString(nested.resolve("drop.tmp"), "needle");

        assertEquals(List.of("nested/keep.tmp"),
                new FindTool().find("*.tmp", directory.toString(), null, null).entries());
        assertEquals(List.of("nested/keep.tmp"), new GrepTool()
                .grep("needle", directory.toString(), "*.tmp", null, null, null, null, null)
                .matches().stream().map(GrepTool.Match::path).toList());
    }

    @Test
    void searchFromSubdirectoryHonorsParentIgnoreFile() throws Exception {
        Files.writeString(directory.resolve("src/local.skip"), "needle");
        Path root = directory.resolve("src");
        assertTrue(new FindTool().find("*.skip", root.toString(), null, null).entries().isEmpty());
        assertEquals(0, new GrepTool().grep("needle", root.toString(), "*.skip",
                null, null, null, null, null).total());
    }

    @Test
    void skipsBinaryFilesAndSearchesExplicitIgnoredFiles() throws Exception {
        Files.write(directory.resolve("binary.txt"), new byte[] {'n', 'e', 'e', 'd', 'l', 'e', 0});
        GrepTool tool = new GrepTool();
        assertEquals(3, tool.grep("needle", directory.toString(), null, null, null, null, null, null).total());
        assertEquals(1, tool.grep("needle", directory.resolve("ignored/hidden.txt").toString(),
                null, null, null, null, null, null).total());
    }

    @Test
    void sharedToolCanRunConcurrentSearches() throws Exception {
        GrepTool tool = new GrepTool();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 16)
                    .<java.util.concurrent.Callable<Long>>mapToObj(ignored -> () ->
                            tool.grep("needle", directory.toString(), "*.txt", true, null, 1, 2, 2).total())
                    .toList();
            for (var result : executor.invokeAll(tasks)) assertEquals(4L, result.get());
        }
    }
}
