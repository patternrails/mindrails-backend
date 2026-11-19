package com.mindrails.backend.knowledge.parser;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Collectors;

/** Word source positions refer to paragraphs and tables, never inferred page numbers. */
final class DocxDocumentParser implements DocumentParser {
    @Override
    public void parse(Path path, DocumentTextCollector output) throws Exception {
        OfficePackageValidator.validate(path, "docx");
        try (var input = Files.newInputStream(path);
                var document = new XWPFDocument(input)) {
            int paragraphNumber = 0;
            String heading = "";
            for (var element : document.getBodyElements()) {
                switch (element) {
                    case XWPFParagraph paragraph -> {
                        paragraphNumber++;
                        if (isHeading(paragraph)) {
                            heading = paragraph.getText();
                        }
                        output.add(
                                paragraph.getText(), heading + " / Paragraph " + paragraphNumber);
                    }
                    case XWPFTable table -> extractTable(table, heading, output);
                    default -> {
                        /* Embedded objects without text do not produce a block. */
                    }
                }
            }
        }
    }

    private boolean isHeading(XWPFParagraph paragraph) {
        return paragraph.getStyle() != null
                && paragraph.getStyle().toLowerCase(Locale.ROOT).contains("heading");
    }

    private void extractTable(XWPFTable table, String heading, DocumentTextCollector output) {
        for (var row : table.getRows()) {
            String text =
                    row.getTableCells().stream()
                            .map(XWPFTableCell::getText)
                            .collect(Collectors.joining(" | "));
            output.add(text, heading + " / Table");
        }
    }
}
