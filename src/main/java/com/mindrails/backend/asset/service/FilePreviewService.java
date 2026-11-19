package com.mindrails.backend.asset.service;

import com.mindrails.backend.asset.config.AssetStorageProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads original file bytes, never indexed/extracted text. Callers authorize before opening. */
@Service
public final class FilePreviewService {
    public record Preview(String filename, String kind, long bytes, String content, String renderedHtml, String mediaType) {
        public Preview(String filename, String kind, long bytes, String content, String renderedHtml) {
            this(filename, kind, bytes, content, renderedHtml, null);
        }
    }
    private final AssetStorageProperties properties;

    public FilePreviewService(AssetStorageProperties properties) { this.properties = properties; }

    public Preview read(String filename, InputStream input) throws IOException {
        String kind = kind(filename);
        int limit = kind.equals("image") ? (int) Math.min(properties.getMaxUploadBytes(), 32L * 1024 * 1024) : properties.getMaxPreviewBytes();
        if (limit < 1 || !kind.equals("image") && limit > 16 * 1024 * 1024)
            throw AssetException.invalid("maxPreviewBytes must be between 1 and 16777216");
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new AssetException(AssetException.Reason.PREVIEW_TOO_LARGE,
                "File exceeds the configured preview size limit; download the original file");
        if (kind.equals("image")) {
            String mediaType = new org.apache.tika.Tika().detect(bytes);
            if (mediaType.equals("image/x-ms-bmp")) mediaType = "image/bmp";
            if (!java.util.Set.of("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/avif").contains(mediaType))
                throw unsupported("File contents are not a supported raster image");
            return new Preview(filename, kind, bytes.length, "", null, mediaType);
        }
        String content;
        try {
            content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw unsupported("Preview requires UTF-8 text; download the original file");
        }
        if (content.startsWith("\uFEFF")) content = content.substring(1);
        if (content.codePoints().anyMatch(c -> (c < 32 && c != '\t' && c != '\n' && c != '\r')
                || (c >= 127 && c <= 159)))
            throw unsupported("Binary content cannot be previewed; download the original file");
        return new Preview(filename, kind, bytes.length, content, kind.equals("html") ? staticHtml(content) : null);
    }

    private static String kind(String filename) {
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "txt" -> "text";
            case "md", "markdown" -> "markdown";
            case "json" -> "json";
            case "html", "htm" -> "html";
            case "csv" -> "csv";
            case "png", "jpg", "jpeg", "webp", "gif", "bmp", "avif" -> "image";
            default -> throw unsupported("This file type does not support preview; download the original file");
        };
    }

    private static AssetException unsupported(String message) {
        return new AssetException(AssetException.Reason.PREVIEW_UNSUPPORTED, message);
    }

    /** Static fallback for JSON consumers; direct hosting uses original content with HTTP sandboxing. */
    static String staticHtml(String content) {
        Document source = Jsoup.parse(content);
        source.select("head style").reversed().forEach(style -> source.body().prependChild(style));
        Safelist safe = Safelist.relaxed()
                .addTags("style", "main", "section", "article", "header", "footer", "nav", "aside", "figure",
                        "figcaption", "div", "span", "h1", "h2", "h3", "h4", "h5", "h6", "details", "summary")
                .addAttributes(":all", "style", "class", "id", "title", "dir", "lang")
                .removeAttributes("a", "href").removeAttributes("blockquote", "cite")
                .removeAttributes("q", "cite").removeAttributes("img", "src")
                .addAttributes("img", "src").addProtocols("img", "src", "data");
        Document clean = new Cleaner(safe).clean(source);
        clean.title(source.title());
        clean.head().appendElement("meta").attr("charset", "utf-8");
        clean.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1");
        for (String attribute : new String[]{"style", "class", "id", "title", "dir", "lang"}) {
            if (source.body().hasAttr(attribute)) clean.body().attr(attribute, source.body().attr(attribute));
            if (source.selectFirst("html").hasAttr(attribute))
                clean.selectFirst("html").attr(attribute, source.selectFirst("html").attr(attribute));
        }
        clean.select("img[src]").forEach(img -> {
            if (!img.attr("src").matches("(?is)^data:image/(?:png|jpeg|gif|webp);base64,[a-z0-9+/=\\s]+$"))
                img.removeAttr("src");
        });
        clean.outputSettings().prettyPrint(false);
        return clean.outerHtml();
    }
}
