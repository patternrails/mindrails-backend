package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.nio.file.Path;
import java.util.zip.ZipFile;

final class OfficePackageValidator {
    private OfficePackageValidator() {}

    static void validate(Path path, String ext) throws Exception {
        try (var zip = new ZipFile(path.toFile())) {
            if (zip.getEntry(ext.equals("docx") ? "word/document.xml" : "ppt/presentation.xml")
                    == null) {
                throw KnowledgeException.invalid("File contents do not match Office type");
            }
            long expanded = zip.stream().mapToLong(e -> Math.max(0, e.getSize())).sum();
            if (expanded > 100_000_000L) {
                throw KnowledgeException.invalid("Expanded Office document exceeds limit");
            }
        }
    }
}
