package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.DocumentBlock;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.apache.tika.Tika;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Detects supported formats and routes to format-specific extractors. */
public final class DocumentParserRegistry {
    private final KnowledgeProperties properties;
    private final Map<String, DocumentParser> parsers;

    public DocumentParserRegistry(KnowledgeProperties properties) {
        this.properties = properties;
        parsers =
                Map.of(
                        "txt",
                        new TextDocumentParser(false),
                        "md",
                        new TextDocumentParser(true),
                        "html",
                        new HtmlDocumentParser(),
                        "htm",
                        new HtmlDocumentParser(),
                        "pdf",
                        new PdfDocumentParser(),
                        "docx",
                        new DocxDocumentParser(),
                        "pptx",
                        new PptxDocumentParser());
    }

    public static String extension(String filename) {
        if (filename == null) {
            throw KnowledgeException.invalid("Filename is required");
        }
        int dot = filename.lastIndexOf('.');
        String ext = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!List.of("txt", "md", "html", "htm", "pdf", "docx", "pptx").contains(ext)) {
            throw KnowledgeException.invalid(
                    "Unsupported file type " + (ext.isBlank() ? "(no extension)" : "." + ext)
                            + ". Supported types: txt, md, html, htm, pdf, docx, pptx");
        }
        return ext;
    }

    public String mediaType(Path path) {
        try {
            return new Tika().detect(path);
        } catch (Exception error) {
            throw KnowledgeException.invalid("Cannot detect document type", error);
        }
    }

    public List<DocumentBlock> parse(Path path, String filename) {
        String extension = extension(filename);
        try {
            if (extension.equals("pdf") && !mediaType(path).equals("application/pdf")) {
                throw KnowledgeException.invalid("File contents do not match PDF type");
            }
            var output = new DocumentTextCollector(properties.getMaxTextChars());
            parsers.get(extension).parse(path, output);
            return output.finish();
        } catch (KnowledgeException error) {
            throw error;
        } catch (Exception error) {
            throw KnowledgeException.invalid(
                    "Cannot parse ." + extension + " document (" + error.getClass().getSimpleName()
                            + "). The file may be corrupt or encrypted.", error);
        }
    }
}
