package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Stateless content search: native ripgrep when available, Java fallback. */
public final class GrepTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("6fc98ba8-7b20-4c4b-9eb3-52484fca57a2");

    private final String executable;

    public GrepTool() { this("rg"); }
    GrepTool(String executable) { this.executable = executable; }

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "grep", value = "Search UTF-8 text file contents. This tool prefers ripgrep (rg) when available and falls back to Java regex. "
            + "Respects .gitignore, includes non-ignored hidden files, and skips binary files. "
            + "Returns paginated matching lines with relative paths, 1-based line numbers and optional context, "
            + "sorted by path then line number, plus total matching lines. Defaults to 100 matches per page. "
            + "Returned line text is capped at 500 characters; use read for complete lines.")
    public Result grep(
            @P(name = "pattern", value = "Regex to search for, or literal text when literal is true.") String pattern,
            @P(name = "path", value = "Required file or directory to search.") String path,
            @P(name = "glob", value = "Optional file glob, for example '*.java'.", required = false) String glob,
            @P(name = "ignoreCase", value = "Ignore letter case. Defaults to false.", required = false) Boolean ignoreCase,
            @P(name = "literal", value = "Treat pattern as literal text instead of regex. Defaults to false.", required = false) Boolean literal,
            @P(name = "context", value = "Number of lines before and after each match. Nonnegative; defaults to 0.", required = false) Integer context,
            @P(name = "page", value = "Page number starting at 1. Defaults to 1.", required = false) Integer page,
            @P(name = "limit", value = "Maximum matching lines per page. Positive; defaults to 100.", required = false) Integer limit)
            throws IOException, InterruptedException {
        if (pattern == null) throw new IllegalArgumentException("pattern must not be null");
        Path target = ToolSupport.path(path);
        if (!Files.exists(target)) throw new NoSuchFileException(target.toString());
        Path root = Files.isDirectory(target) ? target : target.getParent();
        int currentPage = ToolSupport.positive(page, 1, "page");
        int pageSize = ToolSupport.positive(limit, 100, "limit");
        int contextLines = context == null ? 0 : context;
        if (contextLines < 0) throw new IllegalArgumentException("context must not be negative");
        int flags = Boolean.TRUE.equals(ignoreCase) ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        Pattern search;
        try {
            search = Pattern.compile(Boolean.TRUE.equals(literal) ? Pattern.quote(pattern) : pattern, flags);
        } catch (PatternSyntaxException exception) {
            throw new IOException("Invalid regex pattern: " + pattern, exception);
        }

        var files = SearchSupport.files(target, glob);
        if (RipgrepSupport.available(executable, root)) {
            var nativeResult = RipgrepSupport.search(executable, root, files, pattern,
                    Boolean.TRUE.equals(ignoreCase), Boolean.TRUE.equals(literal), contextLines, currentPage, pageSize);
            if (nativeResult != null) return nativeResult;
        }
        long offset = ToolSupport.offset(currentPage, pageSize);
        long total = 0;
        List<PendingMatch> selected = new ArrayList<>();
        for (Path file : files) {
            ToolSupport.checkInterrupted();
            if (isBinary(file)) continue;
            total = scan(file, SearchSupport.relative(root, file), search, contextLines,
                    offset, pageSize, total, selected);
        }
        List<Match> matches = selected.stream().map(PendingMatch::finish).toList();
        return new Result(matches, total, currentPage, pageSize);
    }

    private static long scan(Path file, String path, Pattern search, int context, long offset,
                             int limit, long total, List<PendingMatch> selected) throws IOException {
        Deque<Line> previous = new ArrayDeque<>();
        Deque<PendingMatch> active = new ArrayDeque<>();
        try (var reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            String text;
            long number = 0;
            while ((text = reader.readLine()) != null) {
                ToolSupport.checkInterrupted();
                number++;
                Line current = line(number, text);
                while (!active.isEmpty() && number > active.peekFirst().line() + context) active.removeFirst();
                for (PendingMatch hit : active) hit.after().add(current);
                if (search.matcher(text).find()) {
                    if (total >= offset && selected.size() < limit) {
                        PendingMatch hit = new PendingMatch(path, number, current.text(), current.truncated(),
                                List.copyOf(previous), new ArrayList<>());
                        selected.add(hit);
                        if (context > 0) active.addLast(hit);
                    }
                    total++;
                }
                if (context > 0) {
                    previous.addLast(current);
                    if (previous.size() > context) previous.removeFirst();
                }
            }
        }
        return total;
    }

    private static boolean isBinary(Path file) throws IOException {
        byte[] buffer = new byte[8192];
        try (var input = Files.newInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                ToolSupport.checkInterrupted();
                for (int index = 0; index < read; index++) if (buffer[index] == 0) return true;
            }
        }
        return false;
    }

    static Line line(long number, String text) {
        boolean truncated = text.length() > 500;
        if (!truncated) return new Line(number, text, false);
        int end = Character.isHighSurrogate(text.charAt(499)) ? 499 : 500;
        return new Line(number, text.substring(0, end) + "…", true);
    }

    private record PendingMatch(String path, long line, String text, boolean truncated,
                                List<Line> before, List<Line> after) {
        Match finish() {
            return new Match(path, line, text, truncated, before, after);
        }
    }

    public record Line(long number, String text, boolean truncated) {}

    public record Match(String path, long line, String text, boolean truncated, List<Line> before, List<Line> after) {
        public Match {
            before = List.copyOf(before);
            after = List.copyOf(after);
        }
    }

    public record Result(List<Match> matches, long total, int page, int limit) {
        public Result {
            matches = List.copyOf(matches);
        }
    }
}
