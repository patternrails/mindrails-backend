package com.mindrails.backend.knowledge.parser;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

final class PptxDocumentParser implements DocumentParser {
    @Override
    public void parse(Path path, DocumentTextCollector output) throws Exception {
        OfficePackageValidator.validate(path, "pptx");
        try (var input = Files.newInputStream(path);
                var document = new XMLSlideShow(input)) {
            int slideNumber = 0;
            for (var slide : document.getSlides()) {
                slideNumber++;
                String location = "Slide " + slideNumber;
                for (var shape : slide.getShapes()) {
                    extractShape(shape, location, output);
                }
            }
        }
    }

    private void extractShape(XSLFShape shape, String location, DocumentTextCollector output) {
        switch (shape) {
            case XSLFTextShape text -> output.add(text.getText(), location);
            case XSLFTable table -> {
                for (var row : table.getRows()) {
                    String text =
                            row.getCells().stream()
                                    .map(XSLFTableCell::getText)
                                    .collect(Collectors.joining(" | "));
                    output.add(text, location + " / Table");
                }
            }
            default -> {
                /* Images and other non-text shapes require a separate OCR pipeline. */
            }
        }
    }
}
