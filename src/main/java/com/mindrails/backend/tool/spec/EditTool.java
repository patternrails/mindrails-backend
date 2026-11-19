package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.FileStoragePaths;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.output.structured.Description;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Stateless, exact-text editing; replacements are validated before any bytes are changed. */
public final class EditTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("4f273be8-c3e4-44cf-8c4c-02d5d645998d");
    private final Path storageRoot;

    public EditTool(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "edit", value = "Edit an existing UTF-8 text file using one or more exact text replacements. "
            + "Every oldText must be nonempty and occur exactly once in the original file; replacements must not overlap. "
            + "All replacements refer to the original content, not the results of earlier replacements. "
            + "Preserves a UTF-8 BOM and the file's LF or CRLF line-ending style. Returns replacement count and first changed line.")
    public Result edit(
            @P(name = "path", value = "Path of an existing file inside the configured file storage directory.") String path,
            @P(name = "edits", value = "Nonempty list of disjoint replacements, each containing oldText and newText.") List<Replacement> edits)
            throws IOException {
        Path file = FileStoragePaths.resolveFile(storageRoot, storageRoot, path);
        if (edits == null || edits.isEmpty()) throw new IllegalArgumentException("edits must not be empty");
        List<Replacement> replacements = new ArrayList<>(edits);
        for (Replacement edit : replacements) {
            if (edit == null || edit.oldText() == null || edit.oldText().isEmpty() || edit.newText() == null) {
                throw new IllegalArgumentException("Each edit requires nonempty oldText and non-null newText");
            }
        }
        try (var channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = FileMutationSupport.lock(channel)) {
            String original = FileMutationSupport.read(channel);
            String bom = original.startsWith("\uFEFF") ? "\uFEFF" : "";
            String text = original.substring(bom.length());
            int firstNewline = text.indexOf('\n');
            boolean crlf = firstNewline > 0 && text.charAt(firstNewline - 1) == '\r';
            String normalized = normalize(text);
            List<MatchedReplacement> matches = new ArrayList<>();
            for (Replacement edit : replacements) {
                String oldText = normalize(edit.oldText());
                int start = normalized.indexOf(oldText);
                if (start < 0) throw new IllegalArgumentException("oldText was not found in " + path);
                if (normalized.indexOf(oldText, start + 1) >= 0) {
                    throw new IllegalArgumentException("oldText is not unique in " + path + "; include more context");
                }
                matches.add(new MatchedReplacement(start, start + oldText.length(), normalize(edit.newText())));
            }
            matches.sort(Comparator.comparingInt(MatchedReplacement::start));
            for (int i = 1; i < matches.size(); i++) {
                if (matches.get(i).start() < matches.get(i - 1).end()) {
                    throw new IllegalArgumentException("Replacements overlap; merge them into one edit");
                }
            }
            StringBuilder updated = new StringBuilder(normalized);
            for (int i = matches.size() - 1; i >= 0; i--) {
                MatchedReplacement match = matches.get(i);
                updated.replace(match.start(), match.end(), match.newText());
            }
            if (normalized.contentEquals(updated)) throw new IllegalArgumentException("Edits produced no changes");
            int firstDifference = 0;
            while (firstDifference < normalized.length() && firstDifference < updated.length()
                    && normalized.charAt(firstDifference) == updated.charAt(firstDifference)) firstDifference++;
            long firstChangedLine = 1 + normalized.substring(0, firstDifference).chars().filter(c -> c == '\n').count();
            String output = bom + (crlf ? updated.toString().replace("\n", "\r\n") : updated.toString());
            FileMutationSupport.write(channel, output.getBytes(StandardCharsets.UTF_8));
            return new Result(file.toString(), replacements.size(), firstChangedLine);
        }
    }

    private static String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n');
    }

    public record Replacement(
            @Description("Exact, nonempty text occurring once in the original file") String oldText,
            @Description("Replacement text; use an empty string to delete the matched text") String newText) {}

    public record Result(String path, int replacements, long firstChangedLine) {}

    private record MatchedReplacement(int start, int end, String newText) {}
}
