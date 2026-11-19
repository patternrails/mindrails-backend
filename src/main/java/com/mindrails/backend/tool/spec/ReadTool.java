package com.mindrails.backend.tool.spec;

import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Stateless text/image reader. LC4J transports the returned content blocks. */
public final class ReadTool implements IdentifiedTool {

    public static final UUID ID = UUID.fromString("48a2794a-28e1-4048-abce-f493b3d34e1e");

    @Override
    public UUID id() {
        return ID;
    }

    @Tool(name = "read", value = "Read a UTF-8 text file or a PNG, JPEG, GIF, WebP or BMP image. "
            + "Text results contain content, totalLines, returnedLines and nextOffset for continuation. "
            + "Offsets start at 1; defaults to 2000 lines, with a 50 KiB text output cap. "
            + "Images are returned as image attachments; offset and limit apply only to text.")
    public List<Content> read(
            @P(name = "path", value = "Required file path to read.") String path,
            @P(name = "offset", value = "First line to read, starting at 1. Defaults to 1.", required = false) Integer offset,
            @P(name = "limit", value = "Maximum number of lines to return. Positive; defaults to 2000.", required = false) Integer limit)
            throws IOException {
        Path file = ToolSupport.path(path);
        int start = ToolSupport.positive(offset, 1, "offset");
        int maxLines = ToolSupport.positive(limit, 2000, "limit");
        ToolSupport.checkInterrupted();
        String mime = imageMime(file);
        if (mime != null) {
            return List.of(TextContent.from("Read image " + file), ImageContent.from(file, mime));
        }
        StringBuilder content = new StringBuilder();
        long totalLines = 0;
        int returnedLines = 0;
        int bytes = 0;
        boolean capped = false;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                ToolSupport.checkInterrupted();
                totalLines++;
                if (totalLines < start || returnedLines >= maxLines || capped) continue;
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length + (returnedLines > 0 ? 1 : 0);
                if ((long) bytes + lineBytes > ToolSupport.MAX_OUTPUT_BYTES) {
                    if (returnedLines == 0) throw new IOException("Line " + start + " exceeds the 50 KiB output limit; use bash to inspect it in chunks");
                    capped = true;
                    continue;
                }
                if (returnedLines > 0) content.append('\n');
                content.append(line);
                bytes += lineBytes;
                returnedLines++;
            }
        }
        if (start > totalLines && !(totalLines == 0 && start == 1)) {
            throw new IllegalArgumentException("offset exceeds file length of " + totalLines + " lines");
        }
        long followingLine = (long) start + returnedLines;
        Long nextOffset = followingLine <= totalLines ? followingLine : null;
        var result = new TextResult(file.toString(), content.toString(), start, returnedLines, totalLines, nextOffset, capped);
        return List.of(TextContent.from(ToolSupport.json(result)));
    }

    private static String imageMime(Path file) throws IOException {
        byte[] header;
        try (var input = Files.newInputStream(file)) {
            header = input.readNBytes(12);
        }
        if (header.length >= 8 && Arrays.equals(Arrays.copyOf(header, 8),
                new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10})) return "image/png";
        if (header.length >= 3 && header[0] == (byte) 0xff && header[1] == (byte) 0xd8 && header[2] == (byte) 0xff) return "image/jpeg";
        String magic = new String(header, StandardCharsets.ISO_8859_1);
        if (magic.startsWith("GIF87a") || magic.startsWith("GIF89a")) return "image/gif";
        if (magic.startsWith("RIFF") && magic.length() >= 12 && magic.substring(8, 12).equals("WEBP")) return "image/webp";
        if (magic.startsWith("BM")) return "image/bmp";
        return null;
    }

    public record TextResult(String path, String content, int offset, int returnedLines, long totalLines,
            Long nextOffset, boolean byteLimitReached) {}
}
