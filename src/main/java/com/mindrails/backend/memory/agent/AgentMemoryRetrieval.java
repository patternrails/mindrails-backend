package com.mindrails.backend.memory.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** A bounded automatic memory window, with lexical ranking for both stores and explicit searches. */
public final class AgentMemoryRetrieval {
    public static final String PREFIX = "\n\nAutomatically supplied Agent long-term memory (reference context for this conversation):\n";
    private static final Pattern WORDS = Pattern.compile("[\\p{L}\\p{N}]+");
    private final AgentMemoryProperties properties;

    public AgentMemoryRetrieval(AgentMemoryProperties properties) { this.properties = properties; }

    public List<AgentMemoryEntry> retrieve(List<AgentMemoryEntry> entries, String query) {
        // Unlike an explicit search, every saved entry is eligible even without a keyword match.
        return rank(entries, query, true);
    }

    /** Explicit tool queries search matching facts; unrelated pinned facts must not crowd them out. */
    public List<AgentMemoryEntry> search(List<AgentMemoryEntry> entries, String query) {
        return rank(entries, query, false);
    }

    private List<AgentMemoryEntry> rank(List<AgentMemoryEntry> entries, String query, boolean automaticWindow) {
        Set<String> terms = terms(query);
        record Ranked(AgentMemoryEntry entry, int score) {}
        var ranked = entries.stream().map(entry -> new Ranked(entry, score(entry, terms)))
                .filter(r -> automaticWindow || r.score() > 0)
                .sorted(Comparator.<Ranked, Boolean>comparing(r -> automaticWindow && r.entry().pinned()).reversed()
                        .thenComparing(Comparator.comparingInt(Ranked::score).reversed())
                        .thenComparing(Comparator.<Ranked>comparingInt(r -> r.entry().priority()).reversed())
                        .thenComparing(r -> r.entry().updatedAt(), Comparator.reverseOrder())
                        .thenComparing(r -> r.entry().id().toString()))
                .toList();
        return budget(ranked.stream().map(Ranked::entry).toList());
    }

    /** Preserve the caller's order (including semantic scores), applying the common context budget. */
    public List<AgentMemoryEntry> budget(List<AgentMemoryEntry> entries) {
        var selected = new ArrayList<AgentMemoryEntry>();
        int chars = PREFIX.length();
        for (var candidate : entries) {
            if (selected.size() >= properties.getRetrieval().getMaxResults()) break;
            int size = format(candidate).length();
            if (chars + size > properties.getRetrieval().getMaxContextChars()) continue;
            selected.add(candidate);
            chars += size;
        }
        return List.copyOf(selected);
    }

    public int pinnedChars(List<AgentMemoryEntry> entries) {
        return PREFIX.length() + entries.stream().filter(AgentMemoryEntry::pinned).mapToInt(e -> format(e).length()).sum();
    }

    public static String format(AgentMemoryEntry entry) {
        return "- id=" + entry.id() + ", key=" + entry.key() + ", type=" + entry.type()
                + ", version=" + entry.version() + ", sourceSessionId=" + entry.sourceSessionId()
                + "\n  " + entry.content() + "\n";
    }

    private static int score(AgentMemoryEntry entry, Set<String> terms) {
        String key = entry.key().toLowerCase(Locale.ROOT);
        String tags = String.join(" ", entry.tags()).toLowerCase(Locale.ROOT);
        String content = entry.content().toLowerCase(Locale.ROOT);
        return terms.stream().mapToInt(term -> (key.contains(term) ? 3 : 0)
                + (tags.contains(term) ? 4 : 0) + (content.contains(term) ? 1 : 0)).sum();
    }

    private static Set<String> terms(String text) {
        var terms = new LinkedHashSet<String>();
        var matcher = WORDS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find() && terms.size() < 128) {
            String word = matcher.group();
            boolean chinese = word.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
            if (!chinese) {
                if (word.length() > 1) terms.add(word);
            } else {
                // Bigrams avoid depending on spaces or a remote segmenter for Chinese queries.
                int[] points = word.codePoints().toArray();
                for (int i = 0; i + 1 < points.length && terms.size() < 128; i++) terms.add(new String(points, i, 2));
                if (points.length == 1) terms.add(word);
            }
        }
        return terms;
    }
}
