package com.mindrails.backend.knowledge.parser;

import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

import java.nio.file.Path;

final class PdfDocumentParser implements DocumentParser {
    public void parse(Path path, DocumentTextCollector output) throws Exception {
        try (var pdf = Loader.loadPDF(path.toFile())) {
            if (pdf.isEncrypted()) {
                throw KnowledgeException.invalid("Encrypted PDF is not supported");
            }
            if (pdf.getNumberOfPages() > 2000) {
                throw KnowledgeException.invalid("PDF exceeds page limit");
            }
            var stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                output.add(stripper.getText(pdf), "Page " + page);
            }
        }
        if (output.isEmpty()) {
            throw KnowledgeException.invalid("PDF has no usable text layer; OCR is required");
        }
    }
}
