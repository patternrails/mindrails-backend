package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Stateless directory listing tool. Each invocation supplies its path and owns
 * its directory stream and result state, so instances can be shared across threads.
 */
public final class LsTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("c9f4c881-e4d5-47d3-86b8-fa9fdac85b7c");

    private static final int DEFAULT_LIMIT = 500;

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "ls", value = "List a page of the immediate contents of a directory, including hidden files. "
            + "Entries are sorted alphabetically ignoring case, with '/' after directory names. Does not recurse. "
            + "Returns entries, total (the number of files and directories before pagination), page and limit. "
            + "Pages start at 1; defaults to page 1 with 500 entries per page. Pages beyond the end have empty entries.")
    public Result list(
            @P(name = "path", value = "Directory to list. Required, non-blank path, absolute or relative to "
                    + "the application's current working directory.") String path,
            @P(name = "page", value = "Page number, starting at 1. Must be positive. Defaults to 1.",
                    required = false) Integer page,
            @P(name = "limit", value = "Maximum number of entries per page. Must be positive. Defaults to 500.",
                    required = false) Integer limit) throws IOException {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        int effectivePage = page == null ? 1 : page;
        int effectiveLimit = limit == null ? DEFAULT_LIMIT : limit;
        if (effectivePage <= 0) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (effectiveLimit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        Path directory = Path.of(path).normalize();

        List<Path> allEntries;
        try (Stream<Path> children = Files.list(directory)) {
            allEntries = children
                    .sorted(Comparator.comparing((Path child) -> child.getFileName().toString(),
                            String.CASE_INSENSITIVE_ORDER).thenComparing(child -> child.getFileName().toString()))
                    .toList();
        }

        long offset = ((long) effectivePage - 1) * effectiveLimit;
        List<String> entries = allEntries.stream()
                .skip(offset)
                .limit(effectiveLimit)
                .map(child -> child.getFileName() + (Files.isDirectory(child) ? "/" : ""))
                .toList();
        return new Result(entries, allEntries.size(), effectivePage, effectiveLimit);
    }

    /** Total counts immediate files and directories, including hidden entries, before pagination. */
    public record Result(List<String> entries, long total, int page, int limit) {

        public Result {
            entries = List.copyOf(entries);
        }
    }
}
