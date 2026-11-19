package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Stateless glob search. */
public final class FindTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("9ed426a3-08b3-4ced-a2bc-be8281c4dda7");

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "find", value = "Recursively find files by glob, respecting .gitignore and including non-ignored hidden files. "
            + "Returns paths relative to the search directory, sorted alphabetically "
            + "ignoring case, total matching files, page and limit. Defaults to page 1 and 1000 files per page.")
    public Result find(
            @P(name = "pattern", value = "File glob such as '*.java', '**/*.json' or 'src/**/*.java'.") String pattern,
            @P(name = "path", value = "Required directory to search recursively.") String path,
            @P(name = "page", value = "Page number starting at 1. Defaults to 1.", required = false) Integer page,
            @P(name = "limit", value = "Maximum files per page. Positive; defaults to 1000.", required = false) Integer limit)
            throws IOException, InterruptedException {
        ToolSupport.requireText(pattern, "pattern");
        Path root = ToolSupport.path(path);
        if (!Files.isDirectory(root)) throw new NotDirectoryException(root.toString());
        int currentPage = ToolSupport.positive(page, 1, "page");
        int pageSize = ToolSupport.positive(limit, 1000, "limit");
        List<Path> files = SearchSupport.files(root, pattern);
        List<String> entries = files.stream().skip(ToolSupport.offset(currentPage, pageSize)).limit(pageSize)
                .map(file -> SearchSupport.relative(root, file)).toList();
        return new Result(entries, files.size(), currentPage, pageSize);
    }

    public record Result(List<String> entries, long total, int page, int limit) {
        public Result {
            entries = List.copyOf(entries);
        }
    }
}
