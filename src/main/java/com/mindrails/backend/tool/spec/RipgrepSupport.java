package com.mindrails.backend.tool.spec;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Native JSON transport. File discovery stays shared so rg and Java obey the same ignore/glob rules. */
final class RipgrepSupport {
    private static final JsonMapper JSON = new JsonMapper();

    static boolean available(String executable, Path root) throws InterruptedException {
        try (var run = ProcessSupport.run(List.of(executable, "--version"), root, 3.0, false)) {
            return !run.timedOut() && run.exitCode() == 0;
        } catch (IOException unavailable) { return false; }
    }

    static GrepTool.Result search(String executable, Path root, List<Path> files, String pattern,
            boolean ignoreCase, boolean literal, int context, int page, int limit) throws IOException, InterruptedException {
        var selected = new ArrayList<GrepTool.Match>();
        long total = 0, offset = ToolSupport.offset(page, limit);
        for (int start = 0; start < files.size();) {
            var command = new ArrayList<>(List.of(executable, "--no-config", "--json", "--encoding", "utf-8",
                    "--threads", "1", "--context", String.valueOf(context)));
            if (ignoreCase) command.add("--ignore-case");
            if (literal) command.add("--fixed-strings");
            command.add("--regexp"); command.add(pattern); command.add("--");
            int end = start, chars = 0;
            while (end < files.size() && end - start < 100 && chars < 24000) {
                var path = files.get(end++).toString(); command.add(path); chars += path.length();
            }
            try (var run = ProcessSupport.run(command, root, 60.0, false)) {
                if (run.timedOut()) throw new IOException("ripgrep search timed out");
                // Rust regex lacks some Java features (e.g. lookaround). Keep the existing implementation for those patterns.
                if (run.exitCode() > 1) return null;
                var counts = new HashMap<String, Long>();
                var binary = new HashSet<String>();
                try (var reader = Files.newBufferedReader(run.output(), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ToolSupport.checkInterrupted();
                        var event = JSON.readTree(line); var data = event.path("data");
                        if (event.path("type").asText().equals("match")) counts.merge(value(data.path("path")), 1L, Long::sum);
                        if (event.path("type").asText().equals("end") && !data.path("binary_offset").isNull()
                                && !data.path("binary_offset").isMissingNode()) binary.add(value(data.path("path")));
                    }
                }
                // Determine the page from sorted file totals, then stream JSON again. Memory stays bounded by the requested page/context.
                var ranges = new HashMap<String, Long>();
                for (var file : files.subList(start, end)) {
                    var key = file.toString(); long count = binary.contains(key) ? 0 : counts.getOrDefault(key, 0L);
                    if (total < offset + limit && total + count > offset) ranges.put(key, Math.max(0, offset - total));
                    total += count;
                }
                var byPath = new HashMap<String, List<GrepTool.Match>>();
                var previous = new ArrayDeque<GrepTool.Line>();
                var active = new ArrayList<Pending>();
                var fileMatches = new ArrayList<Pending>();
                String current = null; long ordinal = 0;
                try (var reader = Files.newBufferedReader(run.output(), StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        ToolSupport.checkInterrupted();
                        var event = JSON.readTree(line); var type = event.path("type").asText(); var data = event.path("data");
                        if (type.equals("begin")) {
                            current = value(data.path("path")); ordinal = 0; previous.clear(); active.clear(); fileMatches.clear();
                        } else if ((type.equals("match") || type.equals("context")) && ranges.containsKey(current)) {
                            long number = data.path("line_number").asLong();
                            String content = value(data.path("lines")).replaceFirst("\\r?\\n$", "");
                            var text = GrepTool.line(number, content);
                            previous.removeIf(before -> before.number() < number - context);
                            active.removeIf(hit -> number > hit.line + context);
                            for (var hit : active) hit.after.add(text);
                            if (type.equals("match")) {
                                long localOffset = ranges.get(current);
                                if (ordinal >= localOffset && fileMatches.size() < limit) {
                                    var hit = new Pending(SearchSupport.relative(root, Path.of(current)), text,
                                            List.copyOf(previous), new ArrayList<>());
                                    fileMatches.add(hit); if (context > 0) active.add(hit);
                                }
                                ordinal++;
                            }
                            if (context > 0) { previous.addLast(text); while (previous.size() > context) previous.removeFirst(); }
                        } else if (type.equals("end") && ranges.containsKey(current)) {
                            byPath.put(current, fileMatches.stream().map(Pending::finish).toList());
                        }
                    }
                }
                for (var file : files.subList(start, end)) {
                    for (var hit : byPath.getOrDefault(file.toString(), List.of())) if (selected.size() < limit) selected.add(hit);
                }
            }
            start = end;
        }
        return new GrepTool.Result(selected, total, page, limit);
    }

    private static String value(JsonNode node) {
        return node.has("text") ? node.path("text").asText()
                : new String(Base64.getDecoder().decode(node.path("bytes").asText()), StandardCharsets.UTF_8);
    }

    private static final class Pending {
        final String path; final long line; final GrepTool.Line text;
        final List<GrepTool.Line> before, after;
        Pending(String path, GrepTool.Line text, List<GrepTool.Line> before, List<GrepTool.Line> after) {
            this.path = path; this.line = text.number(); this.text = text; this.before = before; this.after = after;
        }
        GrepTool.Match finish() { return new GrepTool.Match(path, line, text.text(), text.truncated(), before, after); }
    }
}
