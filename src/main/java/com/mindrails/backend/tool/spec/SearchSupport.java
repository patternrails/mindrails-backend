package com.mindrails.backend.tool.spec;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.ignore.IgnoreNode;

/** Per-invocation Java file discovery shared by grep and find. */
final class SearchSupport {

    private static final Comparator<String> PATH_ORDER =
            String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder());

    static List<Path> files(Path target, String glob) throws IOException {
        Path root = Files.isDirectory(target) ? target : target.getParent();
        List<PathMatcher> matchers = globMatchers(glob);
        boolean exclude = glob != null && glob.startsWith("!");
        List<Path> files = new ArrayList<>();
        if (Files.isRegularFile(target)) {
            // An explicitly requested file is searched even if an ignore rule names it.
            if (matches(root, target, matchers, exclude)) files.add(target);
        } else {
            Map<Path, List<IgnoreFile>> inheritedRules = new HashMap<>();
            List<IgnoreFile> ancestorRules = new ArrayList<>();
            Deque<Path> ancestors = new ArrayDeque<>();
            for (Path parent = root.getParent(); parent != null; parent = parent.getParent()) {
                ancestors.addFirst(parent);
            }
            for (Path ancestor : ancestors) addIgnoreFile(ancestor, ancestorRules);
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    ToolSupport.checkInterrupted();
                    if (!directory.equals(root)) {
                        if (directory.getFileName().toString().equals(".git")) return FileVisitResult.SKIP_SUBTREE;
                        if (ignored(directory, true, inheritedRules.get(directory.getParent()))) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    }
                    List<IgnoreFile> rules = new ArrayList<>();
                    rules.addAll(directory.equals(root) ? ancestorRules : inheritedRules.get(directory.getParent()));
                    addIgnoreFile(directory, rules);
                    inheritedRules.put(directory, rules);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    ToolSupport.checkInterrupted();
                    if (attributes.isRegularFile()
                            && !ignored(file, false, inheritedRules.get(file.getParent()))
                            && matches(root, file, matchers, exclude)) files.add(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                    inheritedRules.remove(directory);
                    if (exception != null) throw exception;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        files.sort(Comparator.comparing(file -> relative(root, file), PATH_ORDER));
        return files;
    }

    private static void addIgnoreFile(Path directory, List<IgnoreFile> rules) throws IOException {
        Path ignorePath = directory.resolve(".gitignore");
        if (!Files.isRegularFile(ignorePath)) return;
        IgnoreNode node = new IgnoreNode();
        try (var input = Files.newInputStream(ignorePath)) {
            node.parse(input);
        }
        rules.add(new IgnoreFile(directory, node));
    }

    private static boolean ignored(Path path, boolean directory, List<IgnoreFile> rules) {
        if (rules == null) return false;
        boolean ignored = false;
        for (IgnoreFile rule : rules) {
            String relative = relative(rule.directory(), path);
            switch (rule.node().isIgnored(relative, directory)) {
                case IGNORED -> ignored = true;
                case NOT_IGNORED -> ignored = false;
                case CHECK_PARENT -> { }
            }
        }
        return ignored;
    }

    private static List<PathMatcher> globMatchers(String glob) {
        if (glob == null || glob.isBlank()) return List.of();
        String pattern = glob.startsWith("!") ? glob.substring(1) : glob;
        if (pattern.isEmpty()) throw new IllegalArgumentException("glob must not be empty");
        List<String> alternatives = new ArrayList<>();
        alternatives.add(pattern);
        // NIO's glob treats **/ as requiring a directory; include the zero-directory case.
        for (int index = 0; index < alternatives.size(); index++) {
            String current = alternatives.get(index);
            int position = current.indexOf("**/");
            if (position >= 0 && alternatives.size() < 64) {
                String without = current.substring(0, position) + current.substring(position + 3);
                if (!alternatives.contains(without)) alternatives.add(without);
                int next = current.indexOf("**/", position + 3);
                if (next >= 0) {
                    String reduced = current.substring(0, next) + current.substring(next + 3);
                    if (!alternatives.contains(reduced)) alternatives.add(reduced);
                }
            }
        }
        return alternatives.stream().map(value -> FileSystems.getDefault().getPathMatcher("glob:" + value)).toList();
    }

    private static boolean matches(Path root, Path file, List<PathMatcher> matchers, boolean exclude) {
        if (matchers.isEmpty()) return true;
        Path relative = root.relativize(file);
        boolean matched = matchers.stream().anyMatch(matcher -> matcher.matches(relative)
                || matcher.matches(file.getFileName()));
        return exclude != matched;
    }

    static String relative(Path root, Path file) {
        return root.relativize(file).toString().replace(file.getFileSystem().getSeparator(), "/");
    }

    private record IgnoreFile(Path directory, IgnoreNode node) {}
}
