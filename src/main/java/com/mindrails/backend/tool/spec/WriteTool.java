package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.FileStoragePaths;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** Stateless UTF-8 file writer. */
public final class WriteTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("7432949a-f238-4494-9f0b-53788d885914");
    private final Path storageRoot;

    public WriteTool(Path storageRoot) {
        this.storageRoot = storageRoot.toAbsolutePath().normalize();
    }

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "write", value = "Create or completely overwrite a UTF-8 text file. Creates missing parent "
            + "directories. Use edit for targeted replacements. Returns the path and number of bytes written.")
    public Result write(
            @P(name = "path", value = "File path inside the configured file storage directory.") String path,
            @P(name = "content", value = "Complete file contents. An empty string creates or clears an empty file.") String content)
            throws IOException {
        Path file = FileStoragePaths.resolveFile(storageRoot, storageRoot, path);
        if (content == null) throw new IllegalArgumentException("content must not be null");
        ToolSupport.checkInterrupted();
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(file.getParent());
        file = FileStoragePaths.resolveFile(storageRoot, storageRoot, path);
        try (var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                var lock = FileMutationSupport.lock(channel)) {
            FileMutationSupport.write(channel, bytes);
        }
        return new Result(file.toString(), bytes.length);
    }

    public record Result(String path, long bytesWritten) {}
}
